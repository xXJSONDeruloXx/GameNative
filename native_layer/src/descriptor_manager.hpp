#pragma once

#include <vulkan/vulkan.h>
#include <vector>
#include <array>

namespace GN {
namespace Framegen {

// Simplified descriptor manager for frame generation
class DescriptorManager {
public:
    DescriptorManager(VkDevice device);
    ~DescriptorManager();
    
    VkResult Initialize();
    void Cleanup();
    
    // Allocate descriptor set for frame generation
    VkDescriptorSet AllocateSet();
    
    // Update descriptor set with images/buffers
    void UpdateImageDescriptor(VkDescriptorSet set,
                               uint32_t binding,
                               VkImageView view,
                               VkDescriptorType type);
    
    void UpdateBufferDescriptor(VkDescriptorSet set,
                                uint32_t binding,
                                VkBuffer buffer,
                                VkDeviceSize size,
                                VkDescriptorType type);

private:
    VkDevice device;
    
    VkDescriptorPool pool;
    VkDescriptorSetLayout layout;
    
    // Pre-allocated sets
    std::vector<VkDescriptorSet> availableSets;
    std::vector<VkDescriptorSet> usedSets;
};

} // namespace Framegen
} // namespace GN
