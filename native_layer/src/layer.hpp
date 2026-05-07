#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <memory>
#include <mutex>
#include <unordered_map>

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
};

// Per-swapchain framegen data
struct SwapchainData {
    VkSwapchainKHR swapchain;
    DeviceData* device;
    
    VkExtent2D extent;
    VkFormat format;
    uint32_t imageCount;
    
    // Frame generation state
    uint32_t generationCount;  // multiplier - 1
    uint32_t currentFrame;
    
    // Vulkan objects for frame gen
    VkDescriptorPool descriptorPool;
    VkDescriptorSetLayout descriptorSetLayout;
    VkPipelineLayout pipelineLayout;
    VkPipeline frameGenPipeline;
    
    // Intermediate frame images
    std::vector<VkImage> generatedImages;
    std::vector<VkImageView> generatedImageViews;
    std::vector<VkDeviceMemory> generatedMemory;
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

// Global layer data
class LayerState {
public:
    static LayerState& Get();
    
    LayerConfig config;
    LayerDispatch dispatch;
    
    // Instance data map
    std::mutex instanceMutex;
    std::unordered_map<VkInstance, std::unique_ptr<InstanceData>> instances;
    
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
