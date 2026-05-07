#include "framegen.hpp"
#include "shader_manager.hpp"
#include "descriptor_manager.hpp"

#include <cstring>
#include <algorithm>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GN-Framegen", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GN-Framegen", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

namespace GN {
namespace Framegen {

FrameGenerator::FrameGenerator(VkDevice device, VkPhysicalDevice physicalDevice)
    : device(device)
    , physicalDevice(physicalDevice)
    , opticalFlowShader(VK_NULL_HANDLE)
    , warpShader(VK_NULL_HANDLE)
    , blendShader(VK_NULL_HANDLE)
    , descriptorSetLayout(VK_NULL_HANDLE)
    , pipelineLayout(VK_NULL_HANDLE)
    , opticalFlowPipeline(VK_NULL_HANDLE)
    , warpPipeline(VK_NULL_HANDLE)
    , blendPipeline(VK_NULL_HANDLE)
    , descriptorPool(VK_NULL_HANDLE)
{
}

FrameGenerator::~FrameGenerator() {
    Cleanup();
}

VkResult FrameGenerator::Initialize(VkExtent2D initExtent, VkFormat format, uint32_t generationCount) {
    LOGI("FrameGenerator::Initialize: extent=%dx%d, format=%d, generationCount=%d",
         initExtent.width, initExtent.height, format, generationCount);
    
    this->extent = initExtent;
    
    // Load embedded SPIR-V shaders
    VkResult result = LoadShaders();
    if (result != VK_SUCCESS) {
        LOGE("Failed to load shaders: %d", result);
        return result;
    }
    
    // Create compute pipelines
    result = CreatePipelines(extent, format);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create pipelines: %d", result);
        return result;
    }
    
    // Create intermediate images for generated frames
    result = CreateIntermediateImages(extent, format, generationCount);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create intermediate images: %d", result);
        return result;
    }
    
    LOGI("FrameGenerator initialized successfully");
    return VK_SUCCESS;
}

void FrameGenerator::Cleanup() {
    // Destroy pipelines
    if (opticalFlowPipeline) {
        vkDestroyPipeline(device, opticalFlowPipeline, nullptr);
        opticalFlowPipeline = VK_NULL_HANDLE;
    }
    if (warpPipeline) {
        vkDestroyPipeline(device, warpPipeline, nullptr);
        warpPipeline = VK_NULL_HANDLE;
    }
    if (blendPipeline) {
        vkDestroyPipeline(device, blendPipeline, nullptr);
        blendPipeline = VK_NULL_HANDLE;
    }
    
    // Destroy pipeline layout
    if (pipelineLayout) {
        vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        pipelineLayout = VK_NULL_HANDLE;
    }
    
    // Destroy descriptor set layout
    if (descriptorSetLayout) {
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);
        descriptorSetLayout = VK_NULL_HANDLE;
    }
    
    // Destroy descriptor pool
    if (descriptorPool) {
        vkDestroyDescriptorPool(device, descriptorPool, nullptr);
        descriptorPool = VK_NULL_HANDLE;
    }
    
    // Destroy intermediate images
    for (size_t i = 0; i < generatedImages.size(); i++) {
        if (generatedImageViews[i]) {
            vkDestroyImageView(device, generatedImageViews[i], nullptr);
        }
        if (generatedImages[i]) {
            vkDestroyImage(device, generatedImages[i], nullptr);
        }
        if (generatedMemory[i]) {
            vkFreeMemory(device, generatedMemory[i], nullptr);
        }
    }
    generatedImages.clear();
    generatedImageViews.clear();
    generatedMemory.clear();
    
    // Destroy shader modules
    if (opticalFlowShader) {
        vkDestroyShaderModule(device, opticalFlowShader, nullptr);
        opticalFlowShader = VK_NULL_HANDLE;
    }
    if (warpShader) {
        vkDestroyShaderModule(device, warpShader, nullptr);
        warpShader = VK_NULL_HANDLE;
    }
    if (blendShader) {
        vkDestroyShaderModule(device, blendShader, nullptr);
        blendShader = VK_NULL_HANDLE;
    }
}

uint32_t FrameGenerator::GenerateFrames(VkCommandBuffer cmd,
                                       VkImage currentFrame,
                                       VkImage previousFrame,
                                       VkImage* outGeneratedFrames,
                                       uint32_t maxFrames) {
    if (!opticalFlowPipeline || generatedImages.empty()) {
        LOGE("FrameGenerator not properly initialized");
        return 0;
    }
    
    uint32_t frameCount = std::min(maxFrames, static_cast<uint32_t>(generatedImages.size()));
    if (frameCount == 0) return 0;
    
    // Allocate and update descriptor sets for this frame
    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout;
    
    VkDescriptorSet descriptorSet;
    VkResult result = vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set: %d", result);
        return 0;
    }
    
    // Configure uniform buffer data for frame generation parameters
    struct FramegenParams {
        float flowScale;      // GN_FG_FLOW_SCALE
        uint32_t frameIndex;  // Current intermediate frame index
        uint32_t totalFrames; // Total frames to generate (multiplier - 1)
        float reserved;
    } params = {
        config.flowScale,
        0,  // Will be updated per frame
        frameCount,
        0.0f
    };
    
    // Create uniform buffer (simplified - should use buffer pool in production)
    // For now, we'll use push constants if available, or skip uniform updates
    
    // Step 1: Transition input images to SHADER_READ_OPTIMAL
    VkImageMemoryBarrier barriers[2] = {};
    
    barriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[0].srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    barriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barriers[0].oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    barriers[0].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barriers[0].image = previousFrame;
    barriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barriers[0].subresourceRange.levelCount = 1;
    barriers[0].subresourceRange.layerCount = 1;
    
    barriers[1] = barriers[0];
    barriers[1].image = currentFrame;
    
    vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0,
                        0, nullptr,
                        0, nullptr,
                        2, barriers);
    
    // Step 2: Transition intermediate images to GENERAL for compute
    for (uint32_t i = 0; i < frameCount; i++) {
        VkImageMemoryBarrier barrier = {};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.image = generatedImages[i];
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        
        vkCmdPipelineBarrier(cmd,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            0,
                            0, nullptr,
                            0, nullptr,
                            1, &barrier);
    }
    
    // Step 3: Optical Flow - compute motion vectors
    if (opticalFlowPipeline) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, opticalFlowPipeline);
        
        // Note: Proper descriptor set updates with image views would go here
        // For now, we bind the pipeline - descriptor binding requires complete
        // descriptor set updates with image view handles
        
        uint32_t groupCountX = (extent.width + 15) / 16;
        uint32_t groupCountY = (extent.height + 15) / 16;
        vkCmdDispatch(cmd, groupCountX, groupCountY, 1);
    }
    
    // Step 4: Barrier between optical flow and warp
    // (Flow output → warp input)
    if (warpPipeline) {
        VkMemoryBarrier memBarrier = {};
        memBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        memBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        
        vkCmdPipelineBarrier(cmd,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            0,
                            1, &memBarrier,
                            0, nullptr,
                            0, nullptr);
    }
    
    // Step 5: Warp - warp previous and current frames using flow
    for (uint32_t i = 0; i < frameCount && warpPipeline; i++) {
        params.frameIndex = i;
        // Update uniform buffer or push constants here
        
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, warpPipeline);
        
        uint32_t groupCountX = (extent.width + 15) / 16;
        uint32_t groupCountY = (extent.height + 15) / 16;
        vkCmdDispatch(cmd, groupCountX, groupCountY, 1);
        
        // Barrier between warp iterations if using same intermediate buffers
        if (i < frameCount - 1) {
            VkMemoryBarrier memBarrier = {};
            memBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            memBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            memBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            
            vkCmdPipelineBarrier(cmd,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                0,
                                1, &memBarrier,
                                0, nullptr,
                                0, nullptr);
        }
    }
    
    // Step 6: Blend - combine warped frames
    if (blendPipeline) {
        VkMemoryBarrier memBarrier = {};
        memBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        memBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        
        vkCmdPipelineBarrier(cmd,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            0,
                            1, &memBarrier,
                            0, nullptr,
                            0, nullptr);
        
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, blendPipeline);
        
        uint32_t groupCountX = (extent.width + 15) / 16;
        uint32_t groupCountY = (extent.height + 15) / 16;
        vkCmdDispatch(cmd, groupCountX, groupCountY, 1);
    }
    
    // Step 7: Transition generated images to PRESENT_SRC for swapchain
    for (uint32_t i = 0; i < frameCount; i++) {
        VkImageMemoryBarrier barrier = {};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        barrier.image = generatedImages[i];
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        
        vkCmdPipelineBarrier(cmd,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                            0,
                            0, nullptr,
                            0, nullptr,
                            1, &barrier);
    }
    
    // Return generated images for presentation
    for (uint32_t i = 0; i < frameCount; i++) {
        outGeneratedFrames[i] = generatedImages[i];
    }
    
    // Free descriptor set (simplified - should use pool reset in production)
    vkFreeDescriptorSets(device, descriptorPool, 1, &descriptorSet);
    
    return frameCount;
}

VkResult FrameGenerator::LoadShaders() {
    LOGI("Loading embedded SPIR-V shaders...");
    
    // Load shaders from embedded header
    // Based on interface analysis, shaders use different combinations:
    // - Shaders with binding 32/48 are image processing (optical flow, warp, blend)
    // - Shaders with binding 0/1 are uniform buffer shaders (parameter handling)
    
    // For optical flow: shader_003.spv or shader_004.spv (have 32/48 bindings with images)
    // For warp: shader_005.spv or similar (have 32/48 with images)
    // For blend: shader_006.spv or similar (have 32/48 with images)
    
    std::vector<std::string> candidateFlowShaders = {
        "shader_003.spv", "shader_004.spv", "shader_007.spv"
    };
    std::vector<std::string> candidateWarpShaders = {
        "shader_005.spv", "shader_006.spv", "shader_008.spv"
    };
    std::vector<std::string> candidateBlendShaders = {
        "shader_009.spv", "shader_010.spv", "shader_011.spv"
    };
    
    // Try to load optical flow shader
    for (const auto& name : candidateFlowShaders) {
        auto data = GameScopeVK::Shaders::GetShader(name);
        if (!data.empty()) {
            VkShaderModuleCreateInfo createInfo = {};
            createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            createInfo.codeSize = data.size();
            createInfo.pCode = reinterpret_cast<const uint32_t*>(data.data());
            
            VkResult result = vkCreateShaderModule(device, &createInfo, nullptr, &opticalFlowShader);
            if (result == VK_SUCCESS) {
                LOGI("Loaded optical flow shader: %s", name.c_str());
                break;
            }
        }
    }
    
    // Try to load warp shader
    for (const auto& name : candidateWarpShaders) {
        auto data = GameScopeVK::Shaders::GetShader(name);
        if (!data.empty()) {
            VkShaderModuleCreateInfo createInfo = {};
            createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            createInfo.codeSize = data.size();
            createInfo.pCode = reinterpret_cast<const uint32_t*>(data.data());
            
            VkResult result = vkCreateShaderModule(device, &createInfo, nullptr, &warpShader);
            if (result == VK_SUCCESS) {
                LOGI("Loaded warp shader: %s", name.c_str());
                break;
            }
        }
    }
    
    // Try to load blend shader
    for (const auto& name : candidateBlendShaders) {
        auto data = GameScopeVK::Shaders::GetShader(name);
        if (!data.empty()) {
            VkShaderModuleCreateInfo createInfo = {};
            createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            createInfo.codeSize = data.size();
            createInfo.pCode = reinterpret_cast<const uint32_t*>(data.data());
            
            VkResult result = vkCreateShaderModule(device, &createInfo, nullptr, &blendShader);
            if (result == VK_SUCCESS) {
                LOGI("Loaded blend shader: %s", name.c_str());
                break;
            }
        }
    }
    
    // Validate we have at least some shaders
    if (!opticalFlowShader && !warpShader && !blendShader) {
        LOGE("Failed to load any shaders");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    LOGI("Shaders loaded: flow=%p, warp=%p, blend=%p",
         (void*)opticalFlowShader, (void*)warpShader, (void*)blendShader);
    return VK_SUCCESS;
}

VkResult FrameGenerator::CreatePipelines(VkExtent2D extent, VkFormat format) {
    LOGI("Creating compute pipelines...");
    
    // Create descriptor set layout matching GameScopeVK interface analysis
    // Set 0:
    //   Binding 0: Uniform buffer (config/parameters)
    //   Binding 32: Storage image (input)
    //   Binding 33-47: Intermediate images (flow vectors, etc.)
    //   Binding 48: Storage image (output)
    
    std::vector<VkDescriptorSetLayoutBinding> bindings;
    
    // Binding 0: Uniform buffer
    VkDescriptorSetLayoutBinding binding0 = {};
    binding0.binding = 0;
    binding0.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    binding0.descriptorCount = 1;
    binding0.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    binding0.pImmutableSamplers = nullptr;
    bindings.push_back(binding0);
    
    // Binding 32: Input storage image
    VkDescriptorSetLayoutBinding binding32 = {};
    binding32.binding = 32;
    binding32.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    binding32.descriptorCount = 1;
    binding32.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings.push_back(binding32);
    
    // Binding 48: Output storage image
    VkDescriptorSetLayoutBinding binding48 = {};
    binding48.binding = 48;
    binding48.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    binding48.descriptorCount = 1;
    binding48.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings.push_back(binding48);
    
    // Create descriptor set layout
    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    
    VkResult result = vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &descriptorSetLayout);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout: %d", result);
        return result;
    }
    
    // Create pipeline layout
    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 0;
    pipelineLayoutInfo.pPushConstantRanges = nullptr;
    
    result = vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &pipelineLayout);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout: %d", result);
        return result;
    }
    
    // Create compute pipelines for each loaded shader
    auto createComputePipeline = [&](VkShaderModule shader, VkPipeline& pipeline, const char* name) -> VkResult {
        if (!shader) return VK_SUCCESS;  // Skip if not loaded
        
        VkPipelineShaderStageCreateInfo stageInfo = {};
        stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stageInfo.module = shader;
        stageInfo.pName = "main";  // All GameScopeVK shaders use "main" entry point
        
        VkComputePipelineCreateInfo pipelineInfo = {};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = stageInfo;
        pipelineInfo.layout = pipelineLayout;
        
        result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);
        if (result != VK_SUCCESS) {
            LOGE("Failed to create %s pipeline: %d", name, result);
            return result;
        }
        
        LOGI("Created %s pipeline", name);
        return VK_SUCCESS;
    };
    
    result = createComputePipeline(opticalFlowShader, opticalFlowPipeline, "optical flow");
    if (result != VK_SUCCESS) return result;
    
    result = createComputePipeline(warpShader, warpPipeline, "warp");
    if (result != VK_SUCCESS) return result;
    
    result = createComputePipeline(blendShader, blendPipeline, "blend");
    if (result != VK_SUCCESS) return result;
    
    // Create descriptor pool
    std::array<VkDescriptorPoolSize, 3> poolSizes = {};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[0].descriptorCount = 10;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 30;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 10;
    
    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = 10;
    
    result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool: %d", result);
        return result;
    }
    
    LOGI("Pipelines created successfully");
    return VK_SUCCESS;
}

VkResult FrameGenerator::CreateIntermediateImages(VkExtent2D extent, VkFormat format, uint32_t count) {
    LOGI("Creating %d intermediate images...", count);
    
    generatedImages.resize(count);
    generatedImageViews.resize(count);
    generatedMemory.resize(count);
    
    for (uint32_t i = 0; i < count; i++) {
        // Create image
        VkImageCreateInfo imageInfo = {};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = extent.width;
        imageInfo.extent.height = extent.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = format;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        
        VkResult result = vkCreateImage(device, &imageInfo, nullptr, &generatedImages[i]);
        if (result != VK_SUCCESS) {
            LOGE("Failed to create intermediate image %d: %d", i, result);
            return result;
        }
        
        // Allocate device memory
        VkMemoryRequirements memRequirements;
        vkGetImageMemoryRequirements(device, generatedImages[i], &memRequirements);
        
        VkMemoryAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = FindMemoryType(memRequirements.memoryTypeBits, 
                                                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        
        result = vkAllocateMemory(device, &allocInfo, nullptr, &generatedMemory[i]);
        if (result != VK_SUCCESS) {
            LOGE("Failed to allocate memory for image %d: %d", i, result);
            return result;
        }
        
        result = vkBindImageMemory(device, generatedImages[i], generatedMemory[i], 0);
        if (result != VK_SUCCESS) {
            LOGE("Failed to bind memory for image %d: %d", i, result);
            return result;
        }
        
        // Create image view
        VkImageViewCreateInfo viewInfo = {};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = generatedImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        
        result = vkCreateImageView(device, &viewInfo, nullptr, &generatedImageViews[i]);
        if (result != VK_SUCCESS) {
            LOGE("Failed to create image view %d: %d", i, result);
            return result;
        }
    }
    
    LOGI("Created %d intermediate images", count);
    return VK_SUCCESS;
}

uint32_t FrameGenerator::FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    // Query physical device memory properties
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
    
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && 
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    
    LOGE("Failed to find suitable memory type");
    return 0;  // Fallback
}

} // namespace Framegen
} // namespace GN
