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
    if (std::strcmp(pName, "vkEnumeratePhysicalDevices") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerEnumeratePhysicalDevices);
    }
    if (std::strcmp(pName, "vkEnumeratePhysicalDeviceGroups") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(LayerEnumeratePhysicalDeviceGroups);
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

// Track physical device to instance mapping during enumeration
VKAPI_ATTR VkResult VKAPI_CALL LayerEnumeratePhysicalDevices(
    VkInstance instance,
    uint32_t* pPhysicalDeviceCount,
    VkPhysicalDevice* pPhysicalDevices) {
    
    InstanceData* data = GetInstanceData(instance);
    if (!data) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    PFN_vkEnumeratePhysicalDevices nextEnumeratePhysicalDevices = 
        reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
            data->nextGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    
    if (!nextEnumeratePhysicalDevices) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    VkResult result = nextEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices);
    
    // If we got the list, store the mapping
    if (result == VK_SUCCESS && pPhysicalDevices) {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.physicalDeviceMutex);
        for (uint32_t i = 0; i < *pPhysicalDeviceCount; i++) {
            state.physicalDeviceToInstance[pPhysicalDevices[i]] = instance;
        }
    }
    
    return result;
}

VKAPI_ATTR VkResult VKAPI_CALL LayerEnumeratePhysicalDeviceGroups(
    VkInstance instance,
    uint32_t* pPhysicalDeviceGroupCount,
    VkPhysicalDeviceGroupProperties* pPhysicalDeviceGroupProperties) {
    
    InstanceData* data = GetInstanceData(instance);
    if (!data) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    
    PFN_vkEnumeratePhysicalDeviceGroups nextEnumeratePhysicalDeviceGroups = 
        reinterpret_cast<PFN_vkEnumeratePhysicalDeviceGroups>(
            data->nextGetInstanceProcAddr(instance, "vkEnumeratePhysicalDeviceGroups"));
    
    if (!nextEnumeratePhysicalDeviceGroups) {
        // Not all Vulkan versions have this, chain to next layer
        return data->nextGetInstanceProcAddr(instance, "vkEnumeratePhysicalDeviceGroups") ?
            VK_ERROR_INITIALIZATION_FAILED : VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    
    VkResult result = nextEnumeratePhysicalDeviceGroups(instance, pPhysicalDeviceGroupCount, pPhysicalDeviceGroupProperties);
    
    // If we got the list, store the mapping
    if (result == VK_SUCCESS && pPhysicalDeviceGroupProperties) {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.physicalDeviceMutex);
        for (uint32_t i = 0; i < *pPhysicalDeviceGroupCount; i++) {
            for (uint32_t j = 0; j < pPhysicalDeviceGroupProperties[i].physicalDeviceCount; j++) {
                state.physicalDeviceToInstance[pPhysicalDeviceGroupProperties[i].physicalDevices[j]] = instance;
            }
        }
    }
    
    return result;
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
    
    // Find instance for this physical device (set up during enumeration)
    auto& state = LayerState::Get();
    VkInstance instance = nullptr;
    {
        std::lock_guard<std::mutex> lock(state.physicalDeviceMutex);
        auto it = state.physicalDeviceToInstance.find(physicalDevice);
        if (it != state.physicalDeviceToInstance.end()) {
            instance = it->second;
        }
    }
    
    // Store device data
    auto deviceData = std::make_unique<DeviceData>();
    deviceData->device = *pDevice;
    deviceData->physicalDevice = physicalDevice;
    deviceData->instance = GetInstanceData(instance);  // May be nullptr if not found
    deviceData->nextGetDeviceProcAddr = nextGetDeviceProcAddr;
    
    // Cache function pointers - swapchain
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
    
    // Cache function pointers - shader/pipeline
    deviceData->CreateShaderModule = reinterpret_cast<PFN_vkCreateShaderModule>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateShaderModule"));
    deviceData->DestroyShaderModule = reinterpret_cast<PFN_vkDestroyShaderModule>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyShaderModule"));
    deviceData->CreateDescriptorSetLayout = reinterpret_cast<PFN_vkCreateDescriptorSetLayout>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateDescriptorSetLayout"));
    deviceData->CreatePipelineLayout = reinterpret_cast<PFN_vkCreatePipelineLayout>(
        nextGetDeviceProcAddr(*pDevice, "vkCreatePipelineLayout"));
    deviceData->CreateComputePipelines = reinterpret_cast<PFN_vkCreateComputePipelines>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateComputePipelines"));
    deviceData->CmdBindPipeline = reinterpret_cast<PFN_vkCmdBindPipeline>(
        nextGetDeviceProcAddr(*pDevice, "vkCmdBindPipeline"));
    deviceData->CmdDispatch = reinterpret_cast<PFN_vkCmdDispatch>(
        nextGetDeviceProcAddr(*pDevice, "vkCmdDispatch"));
    deviceData->CmdPipelineBarrier = reinterpret_cast<PFN_vkCmdPipelineBarrier>(
        nextGetDeviceProcAddr(*pDevice, "vkCmdPipelineBarrier"));
    
    // Cache function pointers - command buffer
    deviceData->CreateCommandPool = reinterpret_cast<PFN_vkCreateCommandPool>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateCommandPool"));
    deviceData->DestroyCommandPool = reinterpret_cast<PFN_vkDestroyCommandPool>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyCommandPool"));
    deviceData->AllocateCommandBuffers = reinterpret_cast<PFN_vkAllocateCommandBuffers>(
        nextGetDeviceProcAddr(*pDevice, "vkAllocateCommandBuffers"));
    deviceData->FreeCommandBuffers = reinterpret_cast<PFN_vkFreeCommandBuffers>(
        nextGetDeviceProcAddr(*pDevice, "vkFreeCommandBuffers"));
    deviceData->BeginCommandBuffer = reinterpret_cast<PFN_vkBeginCommandBuffer>(
        nextGetDeviceProcAddr(*pDevice, "vkBeginCommandBuffer"));
    deviceData->EndCommandBuffer = reinterpret_cast<PFN_vkEndCommandBuffer>(
        nextGetDeviceProcAddr(*pDevice, "vkEndCommandBuffer"));
    deviceData->QueueSubmit = reinterpret_cast<PFN_vkQueueSubmit>(
        nextGetDeviceProcAddr(*pDevice, "vkQueueSubmit"));
    deviceData->CreateFence = reinterpret_cast<PFN_vkCreateFence>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateFence"));
    deviceData->DestroyFence = reinterpret_cast<PFN_vkDestroyFence>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyFence"));
    deviceData->WaitForFences = reinterpret_cast<PFN_vkWaitForFences>(
        nextGetDeviceProcAddr(*pDevice, "vkWaitForFences"));
    deviceData->ResetFences = reinterpret_cast<PFN_vkResetFences>(
        nextGetDeviceProcAddr(*pDevice, "vkResetFences"));
    
    // Cache function pointers - image/memory
    deviceData->CreateImage = reinterpret_cast<PFN_vkCreateImage>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateImage"));
    deviceData->DestroyImage = reinterpret_cast<PFN_vkDestroyImage>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyImage"));
    deviceData->CreateImageView = reinterpret_cast<PFN_vkCreateImageView>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateImageView"));
    deviceData->DestroyImageView = reinterpret_cast<PFN_vkDestroyImageView>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyImageView"));
    deviceData->AllocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkAllocateMemory"));
    deviceData->FreeMemory = reinterpret_cast<PFN_vkFreeMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkFreeMemory"));
    deviceData->BindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkBindImageMemory"));
    deviceData->GetImageMemoryRequirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements>(
        nextGetDeviceProcAddr(*pDevice, "vkGetImageMemoryRequirements"));
    deviceData->CreateBuffer = reinterpret_cast<PFN_vkCreateBuffer>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateBuffer"));
    deviceData->DestroyBuffer = reinterpret_cast<PFN_vkDestroyBuffer>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyBuffer"));
    deviceData->BindBufferMemory = reinterpret_cast<PFN_vkBindBufferMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkBindBufferMemory"));
    deviceData->GetBufferMemoryRequirements = reinterpret_cast<PFN_vkGetBufferMemoryRequirements>(
        nextGetDeviceProcAddr(*pDevice, "vkGetBufferMemoryRequirements"));
    deviceData->MapMemory = reinterpret_cast<PFN_vkMapMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkMapMemory"));
    deviceData->UnmapMemory = reinterpret_cast<PFN_vkUnmapMemory>(
        nextGetDeviceProcAddr(*pDevice, "vkUnmapMemory"));
    
    // Cache function pointers - descriptor
    deviceData->CreateDescriptorPool = reinterpret_cast<PFN_vkCreateDescriptorPool>(
        nextGetDeviceProcAddr(*pDevice, "vkCreateDescriptorPool"));
    deviceData->DestroyDescriptorPool = reinterpret_cast<PFN_vkDestroyDescriptorPool>(
        nextGetDeviceProcAddr(*pDevice, "vkDestroyDescriptorPool"));
    deviceData->AllocateDescriptorSets = reinterpret_cast<PFN_vkAllocateDescriptorSets>(
        nextGetDeviceProcAddr(*pDevice, "vkAllocateDescriptorSets"));
    deviceData->FreeDescriptorSets = reinterpret_cast<PFN_vkFreeDescriptorSets>(
        nextGetDeviceProcAddr(*pDevice, "vkFreeDescriptorSets"));
    deviceData->UpdateDescriptorSets = reinterpret_cast<PFN_vkUpdateDescriptorSets>(
        nextGetDeviceProcAddr(*pDevice, "vkUpdateDescriptorSets"));
    
    {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        state.devices[*pDevice] = std::move(deviceData);
    }
    
    LOGI("GN-Framegen: Device created successfully (cached %d function pointers)", 30);
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
    
    // Clean up swapchain-specific resources before removing from map
    {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.swapchainMutex);
        auto it = state.swapchains.find(swapchain);
        if (it != state.swapchains.end() && it->second) {
            SwapchainData* swapchainData = it->second.get();
            
            // Destroy command buffer
            if (swapchainData->commandBuffer != VK_NULL_HANDLE) {
                deviceData->FreeCommandBuffers(device, swapchainData->commandPool, 1, &swapchainData->commandBuffer);
            }
            
            // Destroy command pool
            if (swapchainData->commandPool != VK_NULL_HANDLE) {
                deviceData->DestroyCommandPool(device, swapchainData->commandPool, pAllocator);
            }
            
            // Destroy fence
            if (swapchainData->computeFence != VK_NULL_HANDLE) {
                deviceData->DestroyFence(device, swapchainData->computeFence, pAllocator);
            }
        }
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
    
    VkSwapchainKHR swapchain = pPresentInfo->pSwapchains[0];
    SwapchainData* swapchainData = GetSwapchainData(swapchain);
    
    if (!swapchainData) {
        // Not our swapchain, pass through
        // Find device from queue by checking all devices
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        for (auto& [device, data] : state.devices) {
            if (data->QueuePresentKHR) {
                return data->QueuePresentKHR(queue, pPresentInfo);
            }
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
    
    // Initialize frame generator on first use
    if (!swapchainData->frameGenerator) {
        LOGI("GN-Framegen: Initializing FrameGenerator");
        swapchainData->frameGenerator = std::make_unique<FrameGenerator>(
            swapchainData->device->device,
            swapchainData->device->physicalDevice);
        
        VkResult result = swapchainData->frameGenerator->Initialize(
            swapchainData->extent,
            swapchainData->format,
            swapchainData->generationCount);
        
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to initialize FrameGenerator: %d", result);
            // Fall back to pass-through
            return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
        }
        
        // Apply configuration
        swapchainData->frameGenerator->SetFlowScale(state.config.flowScale);
        swapchainData->frameGenerator->SetModel(state.config.model);
        swapchainData->frameGenerator->SetMultiplier(state.config.multiplier);
    }
    
    // Frame generation requires:
    // 1. Command buffer allocation for compute work
    // 2. Acquiring swapchain images for read-back
    // 3. Running optical flow, warp, and blend compute shaders
    // 4. Presenting generated frames
    
    // Initialize command pool if not already created
    if (swapchainData->commandPool == VK_NULL_HANDLE) {
        // We need a compute-capable queue family
        // For now, use the same queue (assuming compute capability)
        // In production, query queue family properties
        
        VkCommandPoolCreateInfo poolInfo = {};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = 0;  // TODO: Query actual compute queue family
        
        VkResult result = swapchainData->device->CreateCommandPool(
            swapchainData->device->device, &poolInfo, nullptr, &swapchainData->commandPool);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to create command pool: %d", result);
            // Fall back to pass-through
            return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
        }
        
        // Allocate command buffer
        VkCommandBufferAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = swapchainData->commandPool;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = 1;
        
        result = swapchainData->device->AllocateCommandBuffers(
            swapchainData->device->device, &allocInfo, &swapchainData->commandBuffer);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to allocate command buffer: %d", result);
            // Fall back to pass-through
            return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
        }
        
        // Create fence for synchronization
        VkFenceCreateInfo fenceInfo = {};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        
        result = swapchainData->device->CreateFence(
            swapchainData->device->device, &fenceInfo, nullptr, &swapchainData->computeFence);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to create fence: %d", result);
            // Fall back to pass-through
            return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
        }
    }
    
    // TODO: Implement frame history capture
    // To capture swapchain images for frame generation, we need to:
    // 1. Create intermediate images for frame history (swapchainData->frameHistory)
    // 2. Use vkCmdCopyImage to capture current swapchain image
    // 3. Maintain circular buffer of previous/current frames
    // 4. Pass these images to GenerateFrames()
    //
    // For now, we present the real frame and prepare the infrastructure
    
    VkResult result = swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    
    // Update frame counter for tracking previous/current frames
    swapchainData->currentFrame++;
    
    LOGI("GN-Framegen: Presented frame %d (command pool/buffer/fence ready for compute integration)",
         swapchainData->currentFrame);
    
    return result;
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
