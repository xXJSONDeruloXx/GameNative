#include "descriptor_manager.hpp"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GN-Framegen", __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#endif

namespace GN {
namespace Framegen {

DescriptorManager::DescriptorManager(VkDevice device)
    : device(device)
    , pool(VK_NULL_HANDLE)
    , layout(VK_NULL_HANDLE)
{
}

DescriptorManager::~DescriptorManager() {
    Cleanup();
}

VkResult DescriptorManager::Initialize() {
    LOGI("DescriptorManager::Initialize");
    
    // Create descriptor set layout
    // Based on GameScopeVK analysis:
    // - Binding 32, 48: Images (input/output)
    // - Bindings 0, 1: Uniform buffers (config)
    // - Bindings 33-43: Storage buffers (intermediate data)
    
    std::vector<VkDescriptorSetLayoutBinding> bindings;
    
    // Config uniform buffer (binding 0)
    VkDescriptorSetLayoutBinding configBinding = {};
    configBinding.binding = 0;
    configBinding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    configBinding.descriptorCount = 1;
    configBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings.push_back(configBinding);
    
    // Input image (binding 32)
    VkDescriptorSetLayoutBinding inputImageBinding = {};
    inputImageBinding.binding = 32;
    inputImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    inputImageBinding.descriptorCount = 1;
    inputImageBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings.push_back(inputImageBinding);
    
    // Output image (binding 48)
    VkDescriptorSetLayoutBinding outputImageBinding = {};
    outputImageBinding.binding = 48;
    outputImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    outputImageBinding.descriptorCount = 1;
    outputImageBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings.push_back(outputImageBinding);
    
    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    
    VkResult result = vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &layout);
    if (result != VK_SUCCESS) {
        LOGI("Failed to create descriptor set layout: %d", result);
        return result;
    }
    
    // Create descriptor pool
    std::array<VkDescriptorPoolSize, 3> poolSizes = {};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[0].descriptorCount = 16;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    poolSizes[1].descriptorCount = 32;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[2].descriptorCount = 32;
    
    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = 16;
    
    result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &pool);
    if (result != VK_SUCCESS) {
        LOGI("Failed to create descriptor pool: %d", result);
        vkDestroyDescriptorSetLayout(device, layout, nullptr);
        return result;
    }
    
    LOGI("DescriptorManager initialized");
    return VK_SUCCESS;
}

void DescriptorManager::Cleanup() {
    if (pool) {
        vkDestroyDescriptorPool(device, pool, nullptr);
        pool = VK_NULL_HANDLE;
    }
    if (layout) {
        vkDestroyDescriptorSetLayout(device, layout, nullptr);
        layout = VK_NULL_HANDLE;
    }
}

VkDescriptorSet DescriptorManager::AllocateSet() {
    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = pool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &layout;
    
    VkDescriptorSet set;
    VkResult result = vkAllocateDescriptorSets(device, &allocInfo, &set);
    if (result != VK_SUCCESS) {
        LOGI("Failed to allocate descriptor set: %d", result);
        return VK_NULL_HANDLE;
    }
    
    return set;
}

void DescriptorManager::UpdateImageDescriptor(VkDescriptorSet set,
                                              uint32_t binding,
                                              VkImageView view,
                                              VkDescriptorType type) {
    VkDescriptorImageInfo imageInfo = {};
    imageInfo.imageView = view;
    imageInfo.imageLayout = (type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) ?
        VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    
    VkWriteDescriptorSet write = {};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = binding;
    write.dstArrayElement = 0;
    write.descriptorType = type;
    write.descriptorCount = 1;
    write.pImageInfo = &imageInfo;
    
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
}

void DescriptorManager::UpdateBufferDescriptor(VkDescriptorSet set,
                                               uint32_t binding,
                                               VkBuffer buffer,
                                               VkDeviceSize size,
                                               VkDescriptorType type) {
    VkDescriptorBufferInfo bufferInfo = {};
    bufferInfo.buffer = buffer;
    bufferInfo.offset = 0;
    bufferInfo.range = size;
    
    VkWriteDescriptorSet write = {};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = binding;
    write.dstArrayElement = 0;
    write.descriptorType = type;
    write.descriptorCount = 1;
    write.pBufferInfo = &bufferInfo;
    
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
}

} // namespace Framegen
} // namespace GN
