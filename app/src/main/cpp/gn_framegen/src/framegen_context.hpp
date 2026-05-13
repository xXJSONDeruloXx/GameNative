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

// ─── UBO layouts (matching GameScopeVK shader expectations) ─────────────────

struct SynthUBO { float flowScale; float alpha; float epsilon; };
struct FlowUBO   { float flowScale; float pad0; float pad1; float pad2; };
struct PyramidUBO{ uint32_t scale; uint32_t aspect; uint32_t pad0; uint32_t pad1; };

// ─── Pass ────────────────────────────────────────────────────────────────────

class Pass {
public:
    Pass() = default;
    Pass(const vk::Device& dev,
         VkDescriptorPool descPool,
         int embeddedShaderIdx,
         const std::vector<vk::DescriptorBinding>& bindings);

    void destroy(const vk::Device& dev);

    void bindUBO    (const vk::Device& dev, uint32_t binding, const vk::Buffer& buf);
    void bindSampled(const vk::Device& dev, uint32_t binding, const vk::Image&  img,
                     const vk::Sampler& sampler);
    void bindStorage(const vk::Device& dev, uint32_t binding, const vk::Image&  img);

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
    static std::unique_ptr<FramegenContext> create(
        AHardwareBuffer* prevAhb,
        AHardwareBuffer* currAhb,
        const std::vector<AHardwareBuffer*>& outputAhbs,
        VkExtent2D extent,
        VkFormat   format,
        const Config& cfg);

    void present(AHardwareBuffer* newPrevAhb, AHardwareBuffer* newCurrAhb);
#endif

    void updateConfig(const Config& cfg);
    void waitIdle();
    void destroy();

    bool        valid()    const { return device_.valid(); }
    std::string describe() const;

private:
    // ── Vulkan core ──────────────────────────────────────────────────────────
    Config      cfg_;
    VkExtent2D  extent_  = {};
    VkFormat    format_  = VK_FORMAT_R8G8B8A8_UNORM;

    vk::Device      device_;
    vk::CommandPool cmdPool_;
    vk::Sampler     linearSampler_;
    vk::Sampler     nearestSampler_;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;

    // ── AHB-backed images (external, not owned) ───────────────────────────
    vk::Image prevFrame_;
    vk::Image currFrame_;
    std::vector<vk::Image> outputImages_;

    // ── Device-local intermediate images ────────────────────────────────
    // Pyramid (6 levels each at W>>i × H>>i, R8_UNORM)
    std::vector<vk::Image> pyramidA_;   // built from currFrame (frame t)
    std::vector<vk::Image> pyramidB_;   // built from prevFrame (frame t-1)

    // Feature maps (W/2 × H/2, RGBA16F)
    vk::Image featA_;        // shader_05 output
    vk::Image featB_;        // shader_06 output
    vk::Image featChanA_;    // shader_26 output
    vk::Image featChanB_;    // shader_27 output
    vk::Image featChanC_;    // shader_28 output

    // Optical flow (W × H, RGBA16F — packed fwd+bwd)
    vk::Image flowFwd_;      // coarse OF fwd (shader_09)
    vk::Image flowBwd_;      // coarse OF bwd
    vk::Image flowRefinedFwd_;   // after refinement chain
    vk::Image flowRefinedBwd_;

    // Post-processing flow
    vk::Image flowMerged_;
    vk::Image flowExpA_, flowExpB_;

    // Confidence placeholder (W × H, RGBA8, filled white)
    vk::Image confidence_;

    // ── Passes ──────────────────────────────────────────────────────────────
    // Stage 1: Pyramid
    Pass passPyramidA_;    // shader_03 — curr frame → pyramidA_
    Pass passPyramidB_;    // shader_03 — prev frame → pyramidB_ (separate descriptor set)

    // Stage 2: Feature extraction
    Pass passFeatA_;       // shader_05
    Pass passFeatB_;       // shader_06
    Pass passFeatChanA_;   // shader_26
    Pass passFeatChanB_;   // shader_27
    Pass passFeatChanC_;   // shader_28

    // Stage 3: Coarse OF
    Pass passCoarseOF_;    // shader_09 — pyramid (prev @32-34, curr @35-37)

    // Stage 4: OF refinement chain (shaders 08, 10, 11, 12, 17)
    Pass passOFRefine0_;   // shader_08
    Pass passOFRefine1_;   // shader_10
    Pass passOFRefine2_;   // shader_11
    Pass passOFRefine3_;   // shader_12
    Pass passOFRefineLarge_; // shader_17 (final large refinement)

    // Stage 5: Flow post-processing
    Pass passFlowMerge_;   // shader_29
    Pass passFlowExpand_;  // shader_30

    // Stage 6: Synthesis (one per output frame)
    std::vector<Pass> passSynth_;  // shader_04 × (multiplier-1)

    // ── UBO buffers ──────────────────────────────────────────────────────────
    vk::Buffer uboPyramid_;
    vk::Buffer uboFlow_;
    std::vector<vk::Buffer> uboSynth_;

    // ── Per-frame command buffer + fence ─────────────────────────────────────
    struct Frame {
        vk::CommandBuffer cmd;
        vk::Fence         fence;
    };
    Frame    frames_[2];
    uint32_t frameIdx_ = 0;
};

} // namespace gn::framegen
