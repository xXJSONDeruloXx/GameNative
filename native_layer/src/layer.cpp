#include "layer.hpp"

#include <cstdlib>
#include <cstring>
#include <mutex>
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

// Global layer name
const char* const LAYER_NAME = "VK_LAYER_GN_gamescope_framegen";

LayerState& LayerState::Get() {
    static LayerState state;
    return state;
}

LayerConfig LayerConfig::FromEnvironment() {
    LayerConfig config;
    
    const char* enable = std::getenv("GN_FG_ENABLE");
    if (enable) {
        config.enabled = (std::strcmp(enable, "1") == 0 || 
                         std::strcmp(enable, "true") == 0 ||
                         std::strcmp(enable, "TRUE") == 0);
    }
    
    const char* multiplier = std::getenv("GN_FG_MULTIPLIER");
    if (multiplier) {
        int val = std::atoi(multiplier);
        config.multiplier = static_cast<uint32_t>(std::clamp(val, 2, 4));
    }
    
    const char* flowScale = std::getenv("GN_FG_FLOW_SCALE");
    if (flowScale) {
        config.flowScale = std::atof(flowScale);
        config.flowScale = std::clamp(config.flowScale, 0.2f, 1.0f);
    }
    
    const char* model = std::getenv("GN_FG_MODEL");
    if (model) {
        config.model = static_cast<uint32_t>(std::clamp(std::atoi(model), 0, 1));
    }
    
    const char* fpsLimit = std::getenv("GN_FG_FPS_LIMIT");
    if (fpsLimit) {
        config.fpsLimit = static_cast<uint32_t>(std::atoi(fpsLimit));
    }
    
    return config;
}

// Utility function to get chain info
void* GetChainInfo(const void* pNext, VkStructureType type) {
    const VkBaseInStructure* chain = reinterpret_cast<const VkBaseInStructure*>(pNext);
    while (chain) {
        if (chain->sType == type) {
            return const_cast<void*>(static_cast<const void*>(chain));
        }
        chain = chain->pNext;
    }
    return nullptr;
}

// Get instance data or nullptr
static InstanceData* GetInstanceData(VkInstance instance) {
    auto& state = LayerState::Get();
    std::lock_guard<std::mutex> lock(state.instanceMutex);
    auto it = state.instances.find(instance);
    if (it != state.instances.end()) {
        return it->second.get();
    }
    return nullptr;
}

// Get device data or nullptr
static DeviceData* GetDeviceData(VkDevice device) {
    auto& state = LayerState::Get();
    std::lock_guard<std::mutex> lock(state.deviceMutex);
    auto it = state.devices.find(device);
    if (it != state.devices.end()) {
        return it->second.get();
    }
    return nullptr;
}

// Get swapchain data or nullptr
static SwapchainData* GetSwapchainData(VkSwapchainKHR swapchain) {
    auto& state = LayerState::Get();
    std::lock_guard<std::mutex> lock(state.swapchainMutex);
    auto it = state.swapchains.find(swapchain);
    if (it != state.swapchains.end()) {
        return it->second.get();
    }
    return nullptr;
}

// Layer entry points
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL layer_GetInstanceProcAddr(
    VkInstance instance, const char* pName) {
    
    // Core functions we intercept
    if (std::strcmp(pName, "vkCreateInstance") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerCreateInstance);
    }
    if (std::strcmp(pName, "vkDestroyInstance") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerDestroyInstance);
    }
    if (std::strcmp(pName, "vkCreateDevice") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerCreateDevice);
    }
    if (std::strcmp(pName, "vkEnumerateInstanceLayerProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceLayerProperties);
    }
    if (std::strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceExtensionProperties);
    }
    if (std::strcmp(pName, "vkEnumerateDeviceLayerProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateDeviceLayerProperties);
    }
    if (std::strcmp(pName, "vkEnumerateDeviceExtensionProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateDeviceExtensionProperties);
    }
    
    // Get instance data to chain to next layer
    InstanceData* data = GetInstanceData(instance);
    if (data && data->nextGetInstanceProcAddr) {
        return data->nextGetInstanceProcAddr(instance, pName);
    }
    
    return nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL layer_GetDeviceProcAddr(
    VkDevice device, const char* pName) {
    
    // Device functions we intercept
    if (std::strcmp(pName, "vkCreateSwapchainKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerCreateSwapchainKHR);
    }
    if (std::strcmp(pName, "vkDestroySwapchainKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerDestroySwapchainKHR);
    }
    if (std::strcmp(pName, "vkQueuePresentKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerQueuePresentKHR);
    }
    
    // Get device data to chain to next layer
    DeviceData* data = GetDeviceData(device);
    if (data && data->nextGetDeviceProcAddr) {
        return data->nextGetDeviceProcAddr(device, pName);
    }
    
    return nullptr;
}

// Instance management
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateInstance(
    const VkInstanceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkInstance* pInstance) {
    
    LOGI("GN-Framegen: Creating instance");
    
    // Find the next layer's vkCreateInstance
    PFN_vkCreateInstance nextCreateInstance = nullptr;
    PFN_vkGetInstanceProcAddr nextGetInstanceProcAddr = nullptr;
    
    VkLayerInstanceCreateInfo* chainInfo = reinterpret_cast<VkLayerInstanceCreateInfo*>(
        GetChainInfo(pCreateInfo->pNext, VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO));
    
    if (chainInfo && chainInfo->u.pLayerInfo) {
        nextGetInstanceProcAddr = chainInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        nextCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
            nextGetInstanceProcAddr(nullptr, "vkCreateInstance"));
        
        // Advance the chain
        chainInfo->u.pLayerInfo = chainInfo->u.pLayerInfo->pNext;
    }
    
    if (!nextCreateInstance) {
        LOGE("GN-Framegen: Failed to find next vkCreateInstance");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    // Load config
    auto& state = LayerState::Get();
    state.config = LayerConfig::FromEnvironment();
    
    LOGI("GN-Framegen: Enabled=%d Multiplier=%d FlowScale=%.2f Model=%d",
         state.config.enabled,
         state.config.multiplier,
         state.config.flowScale,
         state.config.model);
    
    // Create instance through next layer
    VkResult result = nextCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (result != VK_SUCCESS) {
        return result;
    }
    
    // Store instance data
    auto instanceData = std::make_unique<InstanceData>();
    instanceData->instance = *pInstance;
    instanceData->createFlags = pCreateInfo->flags;
    instanceData->nextGetInstanceProcAddr = nextGetInstanceProcAddr;
    instanceData->nextGetDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        nextGetInstanceProcAddr(*pInstance, "vkGetDeviceProcAddr"));
    
    {
        std::lock_guard<std::mutex> lock(state.instanceMutex);
        state.instances[*pInstance] = std::move(instanceData);
    }
    
    LOGI("GN-Framegen: Instance created successfully");
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL LayerDestroyInstance(
    VkInstance instance,
    const VkAllocationCallbacks* pAllocator) {
    
    LOGI("GN-Framegen: Destroying instance");
    
    auto& state = LayerState::Get();
    PFN_vkDestroyInstance nextDestroyInstance = nullptr;
    
    {
        std::lock_guard<std::mutex> lock(state.instanceMutex);
        auto it = state.instances.find(instance);
        if (it != state.instances.end()) {
            nextDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
                it->second->nextGetInstanceProcAddr(instance, "vkDestroyInstance"));
            state.instances.erase(it);
        }
    }
    
    if (nextDestroyInstance) {
        nextDestroyInstance(instance, pAllocator);
    }
}

// Device management
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateDevice(
    VkPhysicalDevice physicalDevice,
    const VkDeviceCreateInfo* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkDevice* pDevice) {
    
    LOGI("GN-Framegen: Creating device");
    
    // Find the next layer's vkCreateDevice
    PFN_vkCreateDevice nextCreateDevice = nullptr;
    PFN_vkGetDeviceProcAddr nextGetDeviceProcAddr = nullptr;
    
    VkLayerDeviceCreateInfo* chainInfo = reinterpret_cast<VkLayerDeviceCreateInfo*>(
        GetChainInfo(pCreateInfo->pNext, VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO));
    
    if (chainInfo && chainInfo->u.pLayerInfo) {
        PFN_vkGetInstanceProcAddr getInstanceProcAddr = chainInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
        nextGetDeviceProcAddr = chainInfo->u.pLayerInfo->pfnNextGetDeviceProcAddr;
        nextCreateDevice = reinterpret_cast<PFN_vkCreateDevice>(
            getInstanceProcAddr(nullptr, "vkCreateDevice"));
        
        // Advance the chain
        chainInfo->u.pLayerInfo = chainInfo->u.pLayerInfo->pNext;
    }
    
    if (!nextCreateDevice) {
        LOGE("GN-Framegen: Failed to find next vkCreateDevice");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    // Enable required extensions for frame generation
    std::vector<const char*> extensions;
    bool hasExternalMemory = false;
    bool hasExternalMemoryAHB = false;
    
    for (uint32_t i = 0; i < pCreateInfo->enabledExtensionCount; i++) {
        extensions.push_back(pCreateInfo->ppEnabledExtensionNames[i]);
        if (std::strcmp(pCreateInfo->ppEnabledExtensionNames[i], 
                       VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME) == 0) {
            hasExternalMemory = true;
        }
        if (std::strcmp(pCreateInfo->ppEnabledExtensionNames[i],
                       VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) == 0) {
            hasExternalMemoryAHB = true;
        }
    }
    
    // Add required extensions if not present
    if (!hasExternalMemory) {
        extensions.push_back(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
    }
    
    VkDeviceCreateInfo modifiedInfo = *pCreateInfo;
    modifiedInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    modifiedInfo.ppEnabledExtensionNames = extensions.data();
    
    // Create device through next layer
    VkResult result = nextCreateDevice(physicalDevice, &modifiedInfo, pAllocator, pDevice);
    if (result != VK_SUCCESS) {
        return result;
    }
    
    // Get instance data
    VkInstance instance = nullptr; // We need to track this somehow
    // TODO: Link instance to device
    
    // Store device data
    auto deviceData = std::make_unique<DeviceData>();
    deviceData->device = *pDevice;
    deviceData->physicalDevice = physicalDevice;
    deviceData->nextGetDeviceProcAddr = nextGetDeviceProcAddr;
    
    // Cache function pointers
    deviceData->CreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateSwapchainKHR"));
    deviceData->DestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroySwapchainKHR"));
    deviceData->GetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
        nextGetDeviceProcAddr(*pDevice, "vkGetSwapchainImagesKHR"));
    deviceData->AcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
        nextGetDeviceProcAddr(*pDevice, "vkAcquireNextImageKHR"));
    deviceData->QueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(
        nextGetDeviceProcAddr(*pDevice, "vkQueuePresentKHR"));
    
    {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        state.devices[*pDevice] = std::move(deviceData);
    }
    
    LOGI("GN-Framegen: Device created successfully");
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL LayerDestroyDevice(
    VkDevice device,
    const VkAllocationCallbacks* pAllocator) {
    
    LOGI("GN-Framegen: Destroying device");
    
    auto& state = LayerState::Get();
    PFN_vkDestroyDevice nextDestroyDevice = nullptr;
    
    {
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        auto it = state.devices.find(device);
        if (it != state.devices.end()) {
            nextDestroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(
                it->second->nextGetDeviceProcAddr(device, "vkDestroyDevice"));
            state.devices.erase(it);
        }
    }
    
    if (nextDestroyDevice) {
        nextDestroyDevice(device, pAllocator);
    }
}

// Swapchain management
VKAPI_ATTR VkResult VKAPI_CALL LayerCreateSwapchainKHR(
    VkDevice device,
    const VkSwapchainCreateInfoKHR* pCreateInfo,
    const VkAllocationCallbacks* pAllocator,
    VkSwapchainKHR* pSwapchain) {
    
    DeviceData* deviceData = GetDeviceData(device);
    if (!deviceData || !deviceData->CreateSwapchainKHR) {
        return VK_ERROR_DEVICE_LOST;
    }
    
    LOGI("GN-Framegen: Creating swapchain %dx%d, images=%d",
         pCreateInfo->imageExtent.width,
         pCreateInfo->imageExtent.height,
         pCreateInfo->minImageCount);
    
    // Create swapchain through next layer
    VkResult result = deviceData->CreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
    if (result != VK_SUCCESS) {
        return result;
    }
    
    // Store swapchain data for frame generation
    auto swapchainData = std::make_unique<SwapchainData>();
    swapchainData->swapchain = *pSwapchain;
    swapchainData->device = deviceData;
    swapchainData->extent = pCreateInfo->imageExtent;
    swapchainData->format = pCreateInfo->imageFormat;
    swapchainData->currentFrame = 0;
    
    // Get actual image count
    uint32_t imageCount = 0;
    deviceData->GetSwapchainImagesKHR(device, *pSwapchain, &imageCount, nullptr);
    swapchainData->imageCount = imageCount;
    
    // Set generation count based on config
    auto& state = LayerState::Get();
    swapchainData->generationCount = state.config.enabled ? 
        (state.config.multiplier - 1) : 0;
    
    {
        std::lock_guard<std::mutex> lock(state.swapchainMutex);
        state.swapchains[*pSwapchain] = std::move(swapchainData);
    }
    
    LOGI("GN-Framegen: Swapchain created, generationCount=%d",
         state.config.enabled ? (state.config.multiplier - 1) : 0);
    
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL LayerDestroySwapchainKHR(
    VkDevice device,
    VkSwapchainKHR swapchain,
    const VkAllocationCallbacks* pAllocator) {
    
    DeviceData* deviceData = GetDeviceData(device);
    if (!deviceData) {
        return;
    }
    
    LOGI("GN-Framegen: Destroying swapchain");
    
    {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.swapchainMutex);
        state.swapchains.erase(swapchain);
    }
    
    if (deviceData->DestroySwapchainKHR) {
        deviceData->DestroySwapchainKHR(device, swapchain, pAllocator);
    }
}

// Frame generation - THE KEY FUNCTION
VKAPI_ATTR VkResult VKAPI_CALL LayerQueuePresentKHR(
    VkQueue queue,
    const VkPresentInfoKHR* pPresentInfo) {
    
    // Get device from queue (we need to track this)
    // For now, try to get swapchain data
    VkSwapchainKHR swapchain = pPresentInfo->pSwapchains[0];
    SwapchainData* swapchainData = GetSwapchainData(swapchain);
    
    if (!swapchainData) {
        // Not our swapchain, pass through
        DeviceData* deviceData = nullptr; // TODO: Get from queue
        if (deviceData && deviceData->QueuePresentKHR) {
            return deviceData->QueuePresentKHR(queue, pPresentInfo);
        }
        return VK_ERROR_DEVICE_LOST;
    }
    
    auto& state = LayerState::Get();
    
    if (!state.config.enabled || swapchainData->generationCount == 0) {
        // Frame generation disabled, pass through
        return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    }
    
    LOGI("GN-Framegen: Presenting with frame generation (multiplier=%d)",
         state.config.multiplier);
    
    // TODO: Implement actual frame generation
    // 1. Run optical flow on current and previous frames
    // 2. Warp previous frame using flow vectors
    // 3. Blend to generate intermediate frames
    // 4. Present real frame + generated frames
    
    // For now, just pass through
    return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
}

} // namespace Framegen
} // namespace GN

// Exported entry points for layer manifest
extern "C" {

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(
    uint32_t* pPropertyCount,
    VkLayerProperties* pProperties) {
    
    if (pProperties == nullptr) {
        *pPropertyCount = 1;
        return VK_SUCCESS;
    }
    
    if (*pPropertyCount < 1) {
        return VK_INCOMPLETE;
    }
    
    std::strncpy(pProperties[0].layerName, GN::Framegen::LAYER_NAME, VK_MAX_EXTENSION_NAME_SIZE);
    std::strncpy(pProperties[0].description, "GameNative frame generation layer", 
                 VK_MAX_DESCRIPTION_SIZE);
    pProperties[0].implementationVersion = 1;
    pProperties[0].specVersion = VK_API_VERSION_1_3;
    
    *pPropertyCount = 1;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(
    const char* pLayerName,
    uint32_t* pPropertyCount,
    VkExtensionProperties* pProperties) {
    
    if (pLayerName && std::strcmp(pLayerName, GN::Framegen::LAYER_NAME) == 0) {
        *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    
    return VK_ERROR_LAYER_NOT_PRESENT;
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceLayerProperties(
    VkPhysicalDevice physicalDevice,
    uint32_t* pPropertyCount,
    VkLayerProperties* pProperties) {
    
    return vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(
    VkPhysicalDevice physicalDevice,
    const char* pLayerName,
    uint32_t* pPropertyCount,
    VkExtensionProperties* pProperties) {
    
    return vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);
}

} // extern "C"
