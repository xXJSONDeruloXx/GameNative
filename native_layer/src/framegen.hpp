#pragma once

#include <vulkan/vulkan.h>

#ifdef __ANDROID__
#include <vulkan/vulkan_android.h>
#endif

#include <vector>
#include <memory>

namespace GN {
namespace Framegen {

// Frame generation using GameScopeVK's SPIR-V shaders
class FrameGenerator {
public:
    FrameGenerator(VkDevice device, VkPhysicalDevice physicalDevice);
    ~FrameGenerator();
    
    // Initialize frame generation pipeline
    VkResult Initialize(VkExtent2D extent, VkFormat format, uint32_t generationCount);
    void Cleanup();
    
    // Generate intermediate frames between current and previous
    // Returns the number of generated frames ready to present
    uint32_t GenerateFrames(VkCommandBuffer cmd,
                           VkImage currentFrame,
                           VkImage previousFrame,
                           VkImage* outGeneratedFrames,
                           uint32_t maxFrames);
    
    // Shader control parameters (from GameScopeVK)
    void SetFlowScale(float scale) { config.flowScale = scale; }
    void SetModel(uint32_t m) { config.model = m; }
    void SetMultiplier(uint32_t m) { config.multiplier = m; }
    
private:
    VkDevice device;
    VkPhysicalDevice physicalDevice;
    
    // Shader modules (from embedded SPIR-V)
    VkShaderModule opticalFlowShader;
    VkShaderModule warpShader;
    VkShaderModule blendShader;
    
    // Pipeline state
    VkDescriptorSetLayout descriptorSetLayout;
    VkPipelineLayout pipelineLayout;
    VkPipeline opticalFlowPipeline;
    VkPipeline warpPipeline;
    VkPipeline blendPipeline;
    
    // Resources
    VkDescriptorPool descriptorPool;
    std::vector<VkDescriptorSet> descriptorSets;
    
    // Intermediate images for generated frames
    std::vector<VkImage> generatedImages;
    std::vector<VkImageView> generatedImageViews;
    std::vector<VkDeviceMemory> generatedMemory;
    
    // Uniform buffer for frame generation parameters
    VkBuffer uniformBuffer;
    VkDeviceMemory uniformBufferMemory;
    void* uniformBufferMapped;
    
    // Configuration
    struct Config {
        float flowScale = 0.6f;
        uint32_t model = 0;
        uint32_t multiplier = 2;
    } config;
    
    // Uniform buffer layout (std140 alignment)
    struct FramegenParams {
        float flowScale;
        float reserved;
        uint32_t frameIndex;
        uint32_t totalFrames;
    };
    
    // Stored extent for dispatch calculations
    VkExtent2D extent;
    
    // Load embedded SPIR-V shaders
    VkResult LoadShaders();
    VkResult CreatePipelines(VkExtent2D extent, VkFormat format);
    VkResult CreateIntermediateImages(VkExtent2D extent, VkFormat format, uint32_t count);
    VkResult CreateUniformBuffer();
    
    // Memory type helper
    uint32_t FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    
    // Update uniform buffer with current parameters
    void UpdateUniformBuffer(uint32_t frameIndex, uint32_t totalFrames);
    
    // Update descriptor set for a frame generation pass
    void UpdateDescriptorSet(VkDescriptorSet descriptorSet,
                             VkImageView inputImage,
                             VkImageView outputImage,
                             VkImageView flowImage = VK_NULL_HANDLE);
};

} // namespace Framegen
} // namespace GN
