#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <memory>
#include <mutex>
#include <unordered_map>

// Include FrameGenerator
#include "framegen.hpp"

namespace GN {
namespace Framegen {

// Layer configuration from environment
struct LayerConfig {
    bool enabled = true;
    uint32_t multiplier = 2;        // Frame multiplier (2-4)
    float flowScale = 0.6f;         // Flow scale (0.2-1.0)
    uint32_t model = 0;             // Model variant (0=default, 1=clear)
    uint32_t fpsLimit = 0;          // FPS limit (0=unlimited)
    
    static LayerConfig FromEnvironment();
};

// Per-instance data
struct InstanceData {
    VkInstance instance;
    VkInstanceCreateFlags createFlags;
    
    // Next layer's function pointers
    PFN_vkGetInstanceProcAddr nextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr nextGetDeviceProcAddr;
};

// Per-device data
struct DeviceData {
    VkDevice device;
    VkPhysicalDevice physicalDevice;
    InstanceData* instance;
    
    // Queue family indices
    uint32_t graphicsQueueFamily = UINT32_MAX;
    uint32_t computeQueueFamily = UINT32_MAX;
    
    // Cached function pointers from next layer/driver
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdDispatch CmdDispatch;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    
    // Command buffer management
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    
    // Image/memory management
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkMapMemory MapMemory;
    PFN_vkUnmapMemory UnmapMemory;
    
    // Descriptor management
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
};

// Per-swapchain framegen data
struct SwapchainData {
    VkSwapchainKHR swapchain;
    DeviceData* device;
    
    VkExtent2D extent;
    VkFormat format;
    uint32_t imageCount;
    
    // Swapchain images (for copying to frame history)
    std::vector<VkImage> swapchainImages;
    
    // Frame generation state
    uint32_t generationCount;  // multiplier - 1
    uint32_t currentFrame;
    
    // Frame generator instance
    std::unique_ptr<FrameGenerator> frameGenerator;
    
    // Command pool for compute operations
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence computeFence = VK_NULL_HANDLE;
    
    // Frame history for temporal interpolation
    static constexpr uint32_t MAX_FRAME_HISTORY = 2;
    VkImage frameHistory[MAX_FRAME_HISTORY] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkImageView frameHistoryViews[MAX_FRAME_HISTORY] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory frameHistoryMemory[MAX_FRAME_HISTORY] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    uint32_t historyIndex = 0;  // Current index in circular buffer
    
    // Generated frames presentation
    // We generate N-1 interpolated frames between real frames
    // These are stored here after GenerateFrames() and presented in subsequent calls
    std::vector<VkImage> generatedFrames;        // Generated frame images
    std::vector<VkImageView> generatedFrameViews; // Views for presentation
    uint32_t pendingGeneratedFrames = 0;          // Number of generated frames ready to present
    uint32_t nextGeneratedFrameIndex = 0;         // Index of next generated frame to present
};

// Global dispatch table for the layer
struct LayerDispatch {
    // Instance functions we intercept
    PFN_vkCreateInstance CreateInstance;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkEnumerateInstanceLayerProperties EnumerateInstanceLayerProperties;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties;
    PFN_vkEnumerateDeviceLayerProperties EnumerateDeviceLayerProperties;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
};

// Physical device to instance mapping (needed for device creation)
struct PhysicalDeviceInstanceMapping {
    VkPhysicalDevice physicalDevice;
    VkInstance instance;
};

// Global layer data
class LayerState {
public:
    static LayerState& Get();
    
    LayerConfig config;
    LayerDispatch dispatch;
    
    // Instance data map
    std::mutex instanceMutex;
    std::unordered_map<VkInstance, std::unique_ptr<InstanceData>> instances;
    
    // Physical device to instance mapping (populated during enumeration)
    std::mutex physicalDeviceMutex;
    std::unordered_map<VkPhysicalDevice, VkInstance> physicalDeviceToInstance;
    
    // Device data map
    std::mutex deviceMutex;
    std::unordered_map<VkDevice, std::unique_ptr<DeviceData>> devices;
    
    // Swapchain data map
    std::mutex swapchainMutex;
    std::unordered_map<VkSwapchainKHR, std::unique_ptr<SwapchainData>> swapchains;
    
private:
    LayerState() = default;
};

// Exported functions
extern "C" {
    VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL layer_GetInstanceProcAddr(
        VkInstance instance, const char* pName);
    VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL layer_GetDeviceProcAddr(
        VkDevice device, const char* pName);
}

// Helper functions
void* GetChainInfo(const void* pNext, VkStructureType type);
VKAPI_ATTR VkResult VKAPI_CALL LayerEnumeratePhysicalDevices(
    VkInstance instance,
    uint32_t* pPhysicalDeviceCount,
    VkPhysicalDevice* pPhysicalDevices);
VKAPI_ATTR VkResult VKAPI_CALL LayerEnumeratePhysicalDeviceGroups(
    VkInstance instance,
    uint32_t* pPhysicalDeviceGroupCount,
    VkPhysicalDeviceGroupProperties* pPhysicalDeviceGroupProperties);
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateInstance(
    const VkInstanceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkInstance* pInstance);
VKAPI_ATTR void VKAPI_CALL LayerDestroyInstance(
    VkInstance instance,
    const VkAllocationCallbacks* pAllocator);
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateDevice(
    VkPhysicalDevice physicalDevice,
    const VkDeviceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDevice* pDevice);
VKAPI_ATTR void VKAPI_CALL LayerDestroyDevice(
    VkDevice device,
    const VkAllocationCallbacks* pAllocator);
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateSwapchainKHR(
    VkDevice device,
    const VkSwapchainCreateInfoKHR* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSwapchainKHR* pSwapchain);
VKAPI_ATTR void VKAPI_CALL LayerDestroySwapchainKHR(
    VkDevice device,
    VkSwapchainKHR swapchain,
    const VkAllocationCallbacks* pAllocator);
VKAPI_ATTR VkResult VKAPI_CALL LayerQueuePresentKHR(
    VkQueue queue,
    const VkPresentInfoKHR* pPresentInfo);

} // namespace Framegen
} // namespace GN
