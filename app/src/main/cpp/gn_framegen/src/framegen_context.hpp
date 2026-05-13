#pragma once

#include "gn_framegen/config.hpp"
#include "vulkan/vk_types.hpp"
#include "shaders_embedded.hpp"

#include <vulkan/vulkan.h>
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#endif

#include <cstdint>
#include <memory>
#include <vector>

namespace gn::framegen {

// ─── UBO layouts (std140, matching GameScopeVK shader expectations) ──────────

// Synthesis pass (shader_04): {flowScale, alpha, epsilon}
struct SynthUBO {
    float flowScale = 0.6f;
    float alpha     = 0.5f;
    float epsilon   = 1e-5f;
};

// Generic single-float UBO used by flow passes
struct FlowUBO {
    float flowScale = 0.6f;
    float pad0      = 0.0f;
    float pad1      = 0.0f;
    float pad2      = 0.0f;
};

// Pyramid UBO
struct PyramidUBO {
    uint32_t scale  = 2;
    uint32_t aspect = 1;
    uint32_t pad0   = 0;
    uint32_t pad1   = 0;
};

// ─── Pass ────────────────────────────────────────────────────────────────────

// Encapsulates one compute dispatch: pipeline + descriptor set.
class Pass {
public:
    Pass() = default;

    // Create a pass from one of the embedded shaders.
    // bindings: {binding, descriptorType, count} for descriptor set layout
    Pass(const vk::Device& dev,
         VkDescriptorPool descPool,
         int embeddedShaderIdx,
         const std::vector<vk::DescriptorBinding>& bindings);

    void destroy(const vk::Device& dev);

    // Update descriptor bindings before dispatch
    void bindUBO      (const vk::Device& dev, uint32_t binding, const vk::Buffer& buf);
    void bindSampled  (const vk::Device& dev, uint32_t binding, const vk::Image& img,
                       const vk::Sampler& sampler);
    void bindStorage  (const vk::Device& dev, uint32_t binding, const vk::Image& img);

    void dispatch(VkCommandBuffer cmd, uint32_t gx, uint32_t gy) const;

    bool valid() const { return pipeline_.valid(); }

private:
    vk::DescriptorSetLayout layout_;
    vk::DescriptorSet       descSet_;
    vk::ComputePipeline     pipeline_;
};

// ─── FramegenContext ─────────────────────────────────────────────────────────

class FramegenContext {
public:
    FramegenContext() = default;

#ifdef __ANDROID__
    // Creates the context. prevAhb and currAhb are the rotating input AHBs.
    // outputAhbs[multiplier-1] are the generated-frame AHBs.
    static std::unique_ptr<FramegenContext> create(
        AHardwareBuffer* prevAhb,
        AHardwareBuffer* currAhb,
        const std::vector<AHardwareBuffer*>& outputAhbs,
        VkExtent2D extent,
        VkFormat format,
        const Config& cfg);

    // Called each "present" cycle.
    // Swaps the input AHBs (caller must have already written new frames into them),
    // dispatches the pipeline, and signals the output AHBs.
    // After return, all output AHBs contain the generated frames.
    void present(AHardwareBuffer* newPrevAhb,
                 AHardwareBuffer* newCurrAhb);
#endif

    void updateConfig(const Config& cfg);
    void waitIdle();
    void destroy();

    bool valid() const { return device_.valid(); }
    std::string describe() const;

private:
    void buildPipelines();
    void buildDescriptors();
    void dispatchPipeline(VkCommandBuffer cmd, uint32_t frameIdx);

    Config cfg_;
    VkExtent2D extent_ = {0, 0};
    VkFormat format_   = VK_FORMAT_R8G8B8A8_UNORM;

    vk::Device      device_;
    vk::CommandPool cmdPool_;
    vk::Sampler     linearSampler_;
    vk::Sampler     nearestSampler_;

    VkDescriptorPool descPool_ = VK_NULL_HANDLE;

    // AHB-backed input images (not owned here — caller owns the AHBs)
    vk::Image prevFrame_;
    vk::Image currFrame_;

    // AHB-backed output images (not owned here — caller owns the AHBs)
    std::vector<vk::Image> outputImages_;

    // Intermediate device-local images
    std::vector<vk::Image> pyramidA_;       // 6 levels for curr frame
    std::vector<vk::Image> pyramidB_;       // 6 levels for prev frame
    vk::Image flowFwd_;                     // forward flow RGBA16F
    vk::Image flowBwd_;                     // backward flow RGBA16F
    vk::Image flowMerged_;                  // merged flow
    vk::Image flowExpA_, flowExpB_;         // expanded flow A, B
    vk::Image confidence_;                  // placeholder confidence (all 1s)

    // Passes
    Pass passPyramid_;    // shader_03: builds 6-level pyramid
    Pass passCoarseOF_;   // shader_09: coarse optical flow
    Pass passFlowMerge_;  // shader_29: merge fwd+bwd flow
    Pass passFlowExpand_; // shader_30: expand merged flow

    // Per-output synthesis passes (one per generated frame)
    std::vector<Pass> passSynth_;  // shader_04 × (multiplier-1)

    // UBO buffers
    vk::Buffer uboFlow_;     // FlowUBO
    vk::Buffer uboPyramid_;  // PyramidUBO
    std::vector<vk::Buffer> uboSynth_; // SynthUBO × outputs

    // Per-frame command buffer + fence
    struct Frame {
        vk::CommandBuffer cmd;
        vk::Fence         fence;
    };
    Frame frames_[2];
    uint32_t frameIdx_ = 0;
};

} // namespace gn::framegen
