#include "framegen_context.hpp"
#include "logging.hpp"

#include <cstring>
#include <sstream>
#include <stdexcept>

namespace gn::framegen {

// ─── Pass ────────────────────────────────────────────────────────────────────

Pass::Pass(const vk::Device& dev,
           VkDescriptorPool descPool,
           int shaderIdx,
           const std::vector<vk::DescriptorBinding>& bindings) {
    if (shaderIdx < 0 || static_cast<size_t>(shaderIdx) >= embedded::kShaderRegistry.size())
        throw std::runtime_error("Pass: shader index out of range");

    const auto& blob = embedded::kShaderRegistry[static_cast<size_t>(shaderIdx)];
    if (!embedded::IsValidSpirv(blob))
        throw std::runtime_error(std::string("Pass: invalid SPIR-V for ") + blob.name);

    layout_  = vk::DescriptorSetLayout(dev, bindings);
    descSet_ = vk::DescriptorSet(dev, descPool, layout_.handle());
    pipeline_= vk::ComputePipeline(dev, blob.data, blob.size, layout_.handle());

    GNFG_LOGI("Pass: loaded shader %s", blob.name);
}

void Pass::destroy(const vk::Device& dev) {
    pipeline_.destroy(dev);
    layout_.destroy(dev);
}

void Pass::bindUBO(const vk::Device& dev, uint32_t binding, const vk::Buffer& buf) {
    descSet_.bindUBO(dev, binding, buf);
}

void Pass::bindSampled(const vk::Device& dev, uint32_t binding,
                       const vk::Image& img, const vk::Sampler& sampler) {
    descSet_.bindCombinedImageSampler(dev, binding, img, sampler,
        img.external() ? VK_IMAGE_LAYOUT_GENERAL
                       : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}

void Pass::bindStorage(const vk::Device& dev, uint32_t binding, const vk::Image& img) {
    descSet_.bindStorageImage(dev, binding, img);
}

void Pass::dispatch(VkCommandBuffer cmd, uint32_t gx, uint32_t gy) const {
    pipeline_.bind(cmd);
    pipeline_.bindDescriptorSet(cmd, descSet_.handle());
    pipeline_.dispatch(cmd, gx, gy, 1);
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

static void computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb{};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);
}

static void transitionToShaderRead(VkCommandBuffer cmd, vk::Image& img) {
    if (img.external()) return;
    if (img.layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
    vk::imageBarrier(cmd, img.handle(),
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
        img.layout, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    img.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

static void transitionToStorage(VkCommandBuffer cmd, vk::Image& img) {
    if (img.external()) return;
    if (img.layout == VK_IMAGE_LAYOUT_GENERAL) return;
    vk::imageBarrier(cmd, img.handle(),
        img.layout == VK_IMAGE_LAYOUT_UNDEFINED
            ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
        img.layout, VK_IMAGE_LAYOUT_GENERAL);
    img.layout = VK_IMAGE_LAYOUT_GENERAL;
}

// Build a simple descriptor pool large enough for all passes.
static VkDescriptorPool createDescPool(const vk::Device& dev, uint32_t maxSets) {
    std::vector<VkDescriptorPoolSize> sizes = {
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,         maxSets * 4 },
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, maxSets * 16 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          maxSets * 16 },
    };
    VkDescriptorPoolCreateInfo pci{};
    pci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pci.flags         = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    pci.maxSets       = maxSets;
    pci.poolSizeCount = static_cast<uint32_t>(sizes.size());
    pci.pPoolSizes    = sizes.data();
    VkDescriptorPool pool = VK_NULL_HANDLE;
    vkCreateDescriptorPool(dev.handle(), &pci, nullptr, &pool);
    return pool;
}

// Write an all-white image (confidence placeholder) by submitting a fill command.
static void clearImageToOne(const vk::Device& dev,
                            const vk::CommandPool& pool,
                            vk::Image& img) {
    vk::CommandBuffer cb(dev, pool);
    cb.begin();
    // Transition to TRANSFER_DST
    vk::imageBarrier(cb.handle(), img.handle(),
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    img.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    VkClearColorValue clear{};
    clear.float32[0] = 1.0f; clear.float32[1] = 1.0f;
    clear.float32[2] = 1.0f; clear.float32[3] = 1.0f;
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1; range.layerCount = 1;
    vkCmdClearColorImage(cb.handle(), img.handle(),
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
    // Transition to GENERAL so it can be sampled
    vk::imageBarrier(cb.handle(), img.handle(),
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    img.layout = VK_IMAGE_LAYOUT_GENERAL;
    cb.end();
    cb.submitAndWait(dev);
    cb.destroy(dev, pool);
}

// ─── FramegenContext::create ─────────────────────────────────────────────────

#ifdef __ANDROID__
std::unique_ptr<FramegenContext> FramegenContext::create(
        AHardwareBuffer* prevAhb,
        AHardwareBuffer* currAhb,
        const std::vector<AHardwareBuffer*>& outputAhbs,
        VkExtent2D extent,
        VkFormat format,
        const Config& cfg) {
    if (!prevAhb || !currAhb || outputAhbs.empty()) {
        GNFG_LOGE("FramegenContext::create: null AHB or empty outputs");
        return nullptr;
    }
    auto ctx = std::make_unique<FramegenContext>();
    ctx->cfg_    = cfg;
    ctx->cfg_.sanitize();
    ctx->extent_ = extent;
    ctx->format_ = format;

    try {
        ctx->device_       = vk::Device::create();
        ctx->cmdPool_      = vk::CommandPool(ctx->device_);
        ctx->linearSampler_  = vk::Sampler(ctx->device_, VK_FILTER_LINEAR,
                                             VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);
        ctx->nearestSampler_ = vk::Sampler(ctx->device_, VK_FILTER_NEAREST,
                                             VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

        const uint32_t W = extent.width;
        const uint32_t H = extent.height;
        const uint32_t outputs = ctx->cfg_.multiplier - 1;

        ctx->descPool_ = createDescPool(ctx->device_, 64);

        // Import AHB-backed input images
        vk::ImageInfo ahbInfo;
        ahbInfo.extent = extent;
        ahbInfo.format = format;
        ahbInfo.usage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                       | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ahbInfo.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
        ctx->prevFrame_ = vk::Image(ctx->device_, ahbInfo, prevAhb);
        ctx->currFrame_ = vk::Image(ctx->device_, ahbInfo, currAhb);

        // Import AHB-backed output images
        ctx->outputImages_.reserve(outputAhbs.size());
        for (auto* ahb : outputAhbs) {
            ctx->outputImages_.emplace_back(ctx->device_, ahbInfo, ahb);
        }

        // Pyramid levels (device-local, 6 levels, each half the previous)
        ctx->pyramidA_.reserve(6); ctx->pyramidB_.reserve(6);
        for (int i = 0; i < 6; ++i) {
            uint32_t lw = std::max(1u, W >> i);
            uint32_t lh = std::max(1u, H >> i);
            vk::ImageInfo pi;
            pi.extent = {lw, lh};
            pi.format = VK_FORMAT_R8_UNORM;  // luma pyramid
            pi.usage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            ctx->pyramidA_.emplace_back(ctx->device_, pi);
            ctx->pyramidB_.emplace_back(ctx->device_, pi);
        }

        // Flow images (RGBA16F for packed fwd+bwd xy pairs)
        vk::ImageInfo flowInfo;
        flowInfo.extent = extent;
        flowInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        flowInfo.usage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        ctx->flowFwd_    = vk::Image(ctx->device_, flowInfo);
        ctx->flowBwd_    = vk::Image(ctx->device_, flowInfo);
        ctx->flowMerged_ = vk::Image(ctx->device_, flowInfo);
        ctx->flowExpA_   = vk::Image(ctx->device_, flowInfo);
        ctx->flowExpB_   = vk::Image(ctx->device_, flowInfo);

        // Confidence placeholder (RGBA8 all-ones)
        vk::ImageInfo confInfo;
        confInfo.extent = extent;
        confInfo.format = format;
        confInfo.usage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        ctx->confidence_ = vk::Image(ctx->device_, confInfo);
        clearImageToOne(ctx->device_, ctx->cmdPool_, ctx->confidence_);

        // UBO buffers
        PyramidUBO pyubo;
        pyubo.scale = 2; pyubo.aspect = (W > H) ? W / H : H / W;
        ctx->uboPyramid_ = vk::Buffer(ctx->device_,
            sizeof(PyramidUBO), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, &pyubo);

        FlowUBO fubo;
        fubo.flowScale = cfg.flowScale;
        ctx->uboFlow_ = vk::Buffer(ctx->device_,
            sizeof(FlowUBO), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, &fubo);

        ctx->uboSynth_.reserve(outputs);
        for (uint32_t k = 0; k < outputs; ++k) {
            SynthUBO subo;
            subo.flowScale = cfg.flowScale;
            subo.alpha     = static_cast<float>(k + 1) / static_cast<float>(cfg.multiplier);
            subo.epsilon   = 1e-5f;
            ctx->uboSynth_.emplace_back(ctx->device_,
                sizeof(SynthUBO), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, &subo);
        }

        // Build pipelines ─────────────────────────────────────────────────────

        // Pass: pyramid (shader_03)
        // bindings: 0=UBO, 32=SAMPLED, 48-53=STORAGE
        {
            std::vector<vk::DescriptorBinding> binds = {
                {0,  VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
                {32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {49, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {50, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {51, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {52, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {53, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
            };
            ctx->passPyramid_ = Pass(ctx->device_, ctx->descPool_, 3, binds);
            ctx->passPyramid_.bindUBO    (ctx->device_, 0, ctx->uboPyramid_);
            // NB: input and outputs are re-bound per-invocation, see dispatchPipeline
        }

        // Pass: coarse OF (shader_09)
        // bindings: 32-37=SAMPLED (6 pyramid levels), 48-49=STORAGE
        {
            std::vector<vk::DescriptorBinding> binds = {
                {32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {34, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {35, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {36, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {37, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {49, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
            };
            ctx->passCoarseOF_ = Pass(ctx->device_, ctx->descPool_, 9, binds);
            for (int i = 0; i < 3; ++i) {
                ctx->passCoarseOF_.bindSampled(ctx->device_, 32+i,
                    ctx->pyramidA_[i], ctx->linearSampler_);
                ctx->passCoarseOF_.bindSampled(ctx->device_, 35+i,
                    ctx->pyramidB_[i], ctx->linearSampler_);
            }
            ctx->passCoarseOF_.bindStorage(ctx->device_, 48, ctx->flowFwd_);
            ctx->passCoarseOF_.bindStorage(ctx->device_, 49, ctx->flowBwd_);
        }

        // Pass: flow merge (shader_29)
        // bindings: 32-33=SAMPLED, 48=STORAGE
        {
            std::vector<vk::DescriptorBinding> binds = {
                {32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
            };
            ctx->passFlowMerge_ = Pass(ctx->device_, ctx->descPool_, 29, binds);
            ctx->passFlowMerge_.bindSampled(ctx->device_, 32, ctx->flowFwd_, ctx->linearSampler_);
            ctx->passFlowMerge_.bindSampled(ctx->device_, 33, ctx->flowBwd_, ctx->linearSampler_);
            ctx->passFlowMerge_.bindStorage(ctx->device_, 48, ctx->flowMerged_);
        }

        // Pass: flow expand (shader_30)
        // bindings: 32=SAMPLED, 48-49=STORAGE
        {
            std::vector<vk::DescriptorBinding> binds = {
                {32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
                {49, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
            };
            ctx->passFlowExpand_ = Pass(ctx->device_, ctx->descPool_, 30, binds);
            ctx->passFlowExpand_.bindSampled(ctx->device_, 32, ctx->flowMerged_, ctx->linearSampler_);
            ctx->passFlowExpand_.bindStorage(ctx->device_, 48, ctx->flowExpA_);
            ctx->passFlowExpand_.bindStorage(ctx->device_, 49, ctx->flowExpB_);
        }

        // Synthesis passes (shader_04) × (multiplier-1)
        // bindings: 0=UBO, 32-36=SAMPLED (prev, curr, fwdFlow, bwdFlow, confidence), 48=STORAGE
        ctx->passSynth_.reserve(outputs);
        for (uint32_t k = 0; k < outputs; ++k) {
            std::vector<vk::DescriptorBinding> binds = {
                {0,  VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
                {32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {34, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {35, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {36, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
                {48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
            };
            ctx->passSynth_.emplace_back(ctx->device_, ctx->descPool_, 4, binds);
            auto& ps = ctx->passSynth_.back();
            ps.bindUBO    (ctx->device_, 0,  ctx->uboSynth_[k]);
            ps.bindSampled(ctx->device_, 32, ctx->prevFrame_, ctx->linearSampler_);
            ps.bindSampled(ctx->device_, 33, ctx->currFrame_, ctx->linearSampler_);
            ps.bindSampled(ctx->device_, 34, ctx->flowExpA_,  ctx->linearSampler_);
            ps.bindSampled(ctx->device_, 35, ctx->flowExpB_,  ctx->linearSampler_);
            ps.bindSampled(ctx->device_, 36, ctx->confidence_,ctx->linearSampler_);
            ps.bindStorage(ctx->device_, 48, ctx->outputImages_[k]);
        }

        // Frame ring buffers
        for (auto& f : ctx->frames_) {
            f.cmd   = vk::CommandBuffer(ctx->device_, ctx->cmdPool_);
            f.fence = vk::Fence(ctx->device_, true /* start signaled */);
        }

        GNFG_LOGI("FramegenContext ready: %ux%u multiplier=%u shaders=MVP",
                  W, H, cfg.multiplier);
        return ctx;
    } catch (const vk::VkError& e) {
        GNFG_LOGE("FramegenContext::create VkError %d: %s", e.code, e.msg.c_str());
        ctx->destroy();
        return nullptr;
    } catch (const std::exception& e) {
        GNFG_LOGE("FramegenContext::create exception: %s", e.what());
        ctx->destroy();
        return nullptr;
    }
}

void FramegenContext::present(AHardwareBuffer* newPrevAhb, AHardwareBuffer* newCurrAhb) {
    const uint32_t W = extent_.width;
    const uint32_t H = extent_.height;
    const uint32_t fi = frameIdx_ & 1u;
    auto& frame = frames_[fi];

    // Wait for this slot's previous submission to complete
    frame.fence.wait(device_);
    frame.fence.reset(device_);

    // Update AHB-backed images if the caller swapped buffers
    // (re-importing AHBs into existing image handles is not supported — 
    //  for now the context assumes the same AHBs are reused each frame,
    //  with the host writing new pixels into them)
    (void)newPrevAhb; (void)newCurrAhb;

    // Record command buffer
    vkResetCommandBuffer(frame.cmd.handle(), 0);
    frame.cmd.begin();

    VkCommandBuffer cmd = frame.cmd.handle();

    // Acquire AHB-backed inputs from external queue family
    if (prevFrame_.external())
        vk::acquireFromExternal(cmd, prevFrame_, device_.computeFamily(), VK_ACCESS_SHADER_READ_BIT);
    if (currFrame_.external())
        vk::acquireFromExternal(cmd, currFrame_, device_.computeFamily(), VK_ACCESS_SHADER_READ_BIT);

    // === Stage 1: Build pyramid for curr (A) and prev (B) frames ===
    auto doPyramid = [&](vk::Image& srcFrame, std::vector<vk::Image>& pyr) {
        // Bind the specific source image and outputs
        passPyramid_.bindSampled(device_, 32, srcFrame, linearSampler_);
        for (int i = 0; i < 6; ++i) {
            transitionToStorage(cmd, pyr[static_cast<size_t>(i)]);
            passPyramid_.bindStorage(device_, 48+static_cast<uint32_t>(i),
                                     pyr[static_cast<size_t>(i)]);
        }
        passPyramid_.dispatch(cmd, (W+15)/16, (H+15)/16);
        computeBarrier(cmd);
        for (int i = 0; i < 6; ++i)
            transitionToShaderRead(cmd, pyr[static_cast<size_t>(i)]);
    };
    doPyramid(currFrame_, pyramidA_);
    doPyramid(prevFrame_, pyramidB_);

    // === Stage 2: Coarse optical flow ===
    transitionToStorage(cmd, flowFwd_);
    transitionToStorage(cmd, flowBwd_);
    passCoarseOF_.dispatch(cmd, (W+15)/16, (H+15)/16);
    computeBarrier(cmd);
    transitionToShaderRead(cmd, flowFwd_);
    transitionToShaderRead(cmd, flowBwd_);

    // === Stage 3: Flow merge ===
    transitionToStorage(cmd, flowMerged_);
    passFlowMerge_.dispatch(cmd, (W+15)/16, (H+15)/16);
    computeBarrier(cmd);
    transitionToShaderRead(cmd, flowMerged_);

    // === Stage 4: Flow expand ===
    transitionToStorage(cmd, flowExpA_);
    transitionToStorage(cmd, flowExpB_);
    passFlowExpand_.dispatch(cmd, (W+15)/16, (H+15)/16);
    computeBarrier(cmd);
    transitionToShaderRead(cmd, flowExpA_);
    transitionToShaderRead(cmd, flowExpB_);

    // === Stage 5: Synthesis for each output frame ===
    for (size_t k = 0; k < passSynth_.size(); ++k) {
        // Acquire output from external
        if (outputImages_[k].external())
            vk::acquireFromExternal(cmd, outputImages_[k], device_.computeFamily(),
                                    VK_ACCESS_SHADER_WRITE_BIT);

        transitionToStorage(cmd, outputImages_[k]);
        passSynth_[k].dispatch(cmd, (W+15)/16, (H+15)/16);
        computeBarrier(cmd);

        // Release output to external
        if (outputImages_[k].external())
            vk::releaseToExternal(cmd, outputImages_[k], device_.computeFamily(),
                                  VK_ACCESS_SHADER_WRITE_BIT);
    }

    // Release AHB-backed inputs back to external
    if (prevFrame_.external())
        vk::releaseToExternal(cmd, prevFrame_, device_.computeFamily(), VK_ACCESS_SHADER_READ_BIT);
    if (currFrame_.external())
        vk::releaseToExternal(cmd, currFrame_, device_.computeFamily(), VK_ACCESS_SHADER_READ_BIT);

    frame.cmd.end();
    frame.cmd.submit(device_, frame.fence.handle());

    frameIdx_++;
}
#endif // __ANDROID__

void FramegenContext::updateConfig(const Config& cfg) {
    cfg_ = cfg;
    cfg_.sanitize();

    // Update synth UBOs with new flowScale
    for (size_t k = 0; k < uboSynth_.size(); ++k) {
        SynthUBO subo;
        subo.flowScale = cfg_.flowScale;
        subo.alpha     = static_cast<float>(k + 1) / static_cast<float>(cfg_.multiplier);
        subo.epsilon   = 1e-5f;
        uboSynth_[k].write(device_, &subo, sizeof(subo));
    }

    FlowUBO fubo;
    fubo.flowScale = cfg_.flowScale;
    uboFlow_.write(device_, &fubo, sizeof(fubo));
}

void FramegenContext::waitIdle() {
    if (device_.valid()) vkQueueWaitIdle(device_.computeQueue());
}

void FramegenContext::destroy() {
    waitIdle();
    for (auto& f : frames_) {
        f.cmd.destroy(device_, cmdPool_);
        f.fence.destroy(device_);
    }
    // Passes
    passPyramid_.destroy(device_);
    passCoarseOF_.destroy(device_);
    passFlowMerge_.destroy(device_);
    passFlowExpand_.destroy(device_);
    for (auto& ps : passSynth_) ps.destroy(device_);
    passSynth_.clear();

    // UBOs
    uboPyramid_.destroy(device_);
    uboFlow_.destroy(device_);
    for (auto& b : uboSynth_) b.destroy(device_);
    uboSynth_.clear();

    // Images
    prevFrame_.destroy(device_); currFrame_.destroy(device_);
    for (auto& img : outputImages_) img.destroy(device_);
    for (auto& img : pyramidA_) img.destroy(device_);
    for (auto& img : pyramidB_) img.destroy(device_);
    flowFwd_.destroy(device_); flowBwd_.destroy(device_);
    flowMerged_.destroy(device_);
    flowExpA_.destroy(device_); flowExpB_.destroy(device_);
    confidence_.destroy(device_);

    // Descriptor pool
    if (descPool_) vkDestroyDescriptorPool(device_.handle(), descPool_, nullptr);
    descPool_ = VK_NULL_HANDLE;

    // Core
    linearSampler_.destroy(device_);
    nearestSampler_.destroy(device_);
    cmdPool_.destroy(device_);
    device_.destroy();
}

std::string FramegenContext::describe() const {
    std::ostringstream o;
    o << "FramegenContext{" << extent_.width << "x" << extent_.height
      << " mult=" << cfg_.multiplier
      << " flowScale=" << cfg_.flowScale
      << " model=" << cfg_.model
      << " valid=" << (valid() ? "true" : "false")
      << "}";
    return o.str();
}

} // namespace gn::framegen
