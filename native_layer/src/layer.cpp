#include "layer.hpp"

#include <cstdlib>
#include <cstring>
#include <mutex>
#include <algorithm>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GN-Framegen", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GN-Framegen", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GN-Framegen", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGW(...) fprintf(stderr, "WARN: "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#define LOGE(...) fprintf(stderr, "ERROR: "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
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
    deviceData->CmdCopyImage = reinterpret_cast<PFN_vkCmdCopyImage>(
        nextGetDeviceProcAddr(*pDevice, "vkCmdCopyImage"));
    
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
    
    // Query queue family properties for this physical device
    // This is needed for creating command pools with the right queue family
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    
    if (queueFamilyCount > 0) {
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());
        
        // Find graphics and compute queue families
        for (uint32_t i = 0; i < queueFamilyCount; i++) {
            const auto& family = queueFamilies[i];
            
            // Check for graphics support
            if (family.queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                if (deviceData->graphicsQueueFamily == UINT32_MAX) {
                    deviceData->graphicsQueueFamily = i;
                    LOGI("GN-Framegen: Found graphics queue family: %d", i);
                }
            }
            
            // Check for compute support (may be same as graphics)
            if (family.queueFlags & VK_QUEUE_COMPUTE_BIT) {
                if (deviceData->computeQueueFamily == UINT32_MAX) {
                    deviceData->computeQueueFamily = i;
                    LOGI("GN-Framegen: Found compute queue family: %d", i);
                }
                
                // Prefer dedicated compute queue (not graphics) if available
                if ((family.queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    LOGI("GN-Framegen: Found dedicated compute queue family: %d", i);
                }
            }
        }
        
        // If no dedicated compute queue, use graphics queue for compute
        if (deviceData->computeQueueFamily == UINT32_MAX && deviceData->graphicsQueueFamily != UINT32_MAX) {
            deviceData->computeQueueFamily = deviceData->graphicsQueueFamily;
            LOGI("GN-Framegen: Using graphics queue for compute: %d", deviceData->computeQueueFamily);
        }
        
        // Validate we have required queues
        if (deviceData->computeQueueFamily == UINT32_MAX) {
            LOGW("GN-Framegen: No compute queue family found, using fallback 0");
            deviceData->computeQueueFamily = 0;
        }
    } else {
        LOGW("GN-Framegen: Failed to query queue families, using fallback 0");
        deviceData->graphicsQueueFamily = 0;
        deviceData->computeQueueFamily = 0;
    }
    
    {
        auto& state = LayerState::Get();
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        state.devices[*pDevice] = std::move(deviceData);
    }
    
    LOGI("GN-Framegen: Device created successfully (cached %d function pointers, graphics=%d, compute=%d)", 
         30, deviceData->graphicsQueueFamily, deviceData->computeQueueFamily);
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

// Helper: Create frame history images for swapchain
// Find memory type that has the required properties
static uint32_t FindMemoryType(VkPhysicalDevice physicalDevice, uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
    
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && 
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    
    // Fallback: return first available type
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if (typeFilter & (1 << i)) {
            LOGW("GN-Framegen: Falling back to memory type %d (requested properties 0x%x)", 
                 i, properties);
            return i;
        }
    }
    
    return 0; // Last resort fallback
}

static VkResult CreateFrameHistoryImages(SwapchainData* swapchainData, VkDevice device, 
                                          DeviceData* deviceData, VkAllocationCallbacks* pAllocator) {
    // Get physical device for memory type queries
    auto& state = LayerState::Get();
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(state.deviceMutex);
        auto it = state.devices.find(device);
        if (it != state.devices.end() && it->second) {
            // Find physical device from instance mapping
            std::lock_guard<std::mutex> pdLock(state.physicalDeviceMutex);
            for (const auto& [pd, inst] : state.physicalDeviceToInstance) {
                if (inst == it->second->instance) {
                    physicalDevice = pd;
                    break;
                }
            }
        }
    }
    
    if (physicalDevice == VK_NULL_HANDLE) {
        LOGW("GN-Framegen: Physical device not found, using memory type 0 fallback");
    }
    
    // Create 2 frame history buffers (previous and current)
    for (uint32_t i = 0; i < SwapchainData::MAX_FRAME_HISTORY; i++) {
        // Create image
        VkImageCreateInfo imageInfo = {};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.extent.width = swapchainData->extent.width;
        imageInfo.extent.height = swapchainData->extent.height;
        imageInfo.extent.depth = 1;
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.format = swapchainData->format;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        
        VkResult result = deviceData->CreateImage(device, &imageInfo, pAllocator, &swapchainData->frameHistory[i]);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to create frame history image %d: %d", i, result);
            // Cleanup already created images
            for (uint32_t j = 0; j < i; j++) {
                deviceData->DestroyImage(device, swapchainData->frameHistory[j], pAllocator);
            }
            return result;
        }
        
        // Get memory requirements
        VkMemoryRequirements memRequirements;
        deviceData->GetImageMemoryRequirements(device, swapchainData->frameHistory[i], &memRequirements);
        
        // Allocate memory with proper type selection
        VkMemoryAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        
        // Find device-local memory type
        if (physicalDevice != VK_NULL_HANDLE) {
            allocInfo.memoryTypeIndex = FindMemoryType(
                physicalDevice, 
                memRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        } else {
            allocInfo.memoryTypeIndex = 0; // Fallback
        }
        
        result = deviceData->AllocateMemory(device, &allocInfo, pAllocator, &swapchainData->frameHistoryMemory[i]);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to allocate frame history memory %d: %d", i, result);
            deviceData->DestroyImage(device, swapchainData->frameHistory[i], pAllocator);
            for (uint32_t j = 0; j < i; j++) {
                deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[j], pAllocator);
                deviceData->DestroyImage(device, swapchainData->frameHistory[j], pAllocator);
            }
            return result;
        }
        
        // Bind memory
        result = deviceData->BindImageMemory(device, swapchainData->frameHistory[i], 
                                             swapchainData->frameHistoryMemory[i], 0);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to bind frame history memory %d: %d", i, result);
            deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[i], pAllocator);
            deviceData->DestroyImage(device, swapchainData->frameHistory[i], pAllocator);
            for (uint32_t j = 0; j < i; j++) {
                deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[j], pAllocator);
                deviceData->DestroyImage(device, swapchainData->frameHistory[j], pAllocator);
            }
            return result;
        }
        
        // Create image view
        VkImageViewCreateInfo viewInfo = {};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = swapchainData->frameHistory[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainData->format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        
        result = deviceData->CreateImageView(device, &viewInfo, pAllocator, &swapchainData->frameHistoryViews[i]);
        if (result != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to create frame history view %d: %d", i, result);
            deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[i], pAllocator);
            deviceData->DestroyImage(device, swapchainData->frameHistory[i], pAllocator);
            for (uint32_t j = 0; j < i; j++) {
                deviceData->DestroyImageView(device, swapchainData->frameHistoryViews[j], pAllocator);
                deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[j], pAllocator);
                deviceData->DestroyImage(device, swapchainData->frameHistory[j], pAllocator);
            }
            return result;
        }
    }
    
    LOGI("GN-Framegen: Created %d frame history images (%dx%d)", 
         SwapchainData::MAX_FRAME_HISTORY,
         swapchainData->extent.width, swapchainData->extent.height);
    return VK_SUCCESS;
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
    
    // Get actual image count and retrieve swapchain images
    uint32_t imageCount = 0;
    deviceData->GetSwapchainImagesKHR(device, *pSwapchain, &imageCount, nullptr);
    swapchainData->imageCount = imageCount;
    
    // Store swapchain image handles for copying to frame history
    swapchainData->swapchainImages.resize(imageCount);
    if (imageCount > 0) {
        VkResult imageResult = deviceData->GetSwapchainImagesKHR(device, *pSwapchain, &imageCount, 
                                                                  swapchainData->swapchainImages.data());
        if (imageResult != VK_SUCCESS) {
            LOGI("GN-Framegen: Failed to get swapchain images: %d", imageResult);
            swapchainData->swapchainImages.clear();
        } else {
            LOGI("GN-Framegen: Retrieved %d swapchain images", imageCount);
        }
    }
    
    // Set generation count based on config
    auto& state = LayerState::Get();
    swapchainData->generationCount = state.config.enabled ? 
        (state.config.multiplier - 1) : 0;
    
    // Create frame history images for temporal frame generation
    if (state.config.enabled && swapchainData->generationCount > 0) {
        VkResult historyResult = CreateFrameHistoryImages(swapchainData.get(), device, deviceData, 
                                                          const_cast<VkAllocationCallbacks*>(pAllocator));
        if (historyResult != VK_SUCCESS) {
            LOGE("GN-Framegen: Failed to create frame history images: %d", historyResult);
            // Continue without frame generation
            swapchainData->generationCount = 0;
        }
    }
    
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
            
            // Destroy frame history images, views, and memory
            for (uint32_t i = 0; i < SwapchainData::MAX_FRAME_HISTORY; i++) {
                if (swapchainData->frameHistoryViews[i] != VK_NULL_HANDLE) {
                    deviceData->DestroyImageView(device, swapchainData->frameHistoryViews[i], pAllocator);
                }
                if (swapchainData->frameHistory[i] != VK_NULL_HANDLE) {
                    deviceData->DestroyImage(device, swapchainData->frameHistory[i], pAllocator);
                }
                if (swapchainData->frameHistoryMemory[i] != VK_NULL_HANDLE) {
                    deviceData->FreeMemory(device, swapchainData->frameHistoryMemory[i], pAllocator);
                }
            }
            
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
        // Use the compute queue family discovered during device creation
        // Falls back to 0 if discovery failed
        
        VkCommandPoolCreateInfo poolInfo = {};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = swapchainData->device->computeQueueFamily;
        
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
    
    // Capture frame history and generate interpolated frames
    // Frame generation workflow:
    // 1. Record command buffer: capture current swapchain image to frameHistory
    // 2. If we have previous frame, run compute: optical flow -> warp -> blend
    // 3. Submit compute work
    // 4. Wait for compute completion
    // 5. Present real frame + generated frames
    
    // We need access to the current swapchain image index
    // This is available from the application's acquire next image call
    // For now, we'll use a placeholder approach
    
    // Record command buffer to capture current frame
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    
    VkResult result = swapchainData->device->BeginCommandBuffer(swapchainData->commandBuffer, &beginInfo);
    if (result != VK_SUCCESS) {
        LOGE("GN-Framegen: Failed to begin command buffer: %d", result);
        return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    }
    
    // Get current history index (where we'll store this frame)
    uint32_t currentHistoryIdx = swapchainData->historyIndex % SwapchainData::MAX_FRAME_HISTORY;
    uint32_t previousHistoryIdx = (swapchainData->historyIndex + 1) % SwapchainData::MAX_FRAME_HISTORY;
    
    // Transition frame history image to TRANSFER_DST for capture
    // Note: We need the current swapchain image index from the present info
    // VkImage swapchainImage = pPresentInfo->pImages[0]; // Not available in present info
    // 
    // Since we don't have direct access to the swapchain image being presented,
    // we need to maintain our own copy. The application renders to the swapchain
    // image, then calls QueuePresentKHR. We need to copy that image before present.
    //
    // One approach: The pPresentInfo contains indices via pImageIndices, but not
    // the actual VkImage handles. We'd need to track which images belong to which
    // indices from CreateSwapchainKHR/GetSwapchainImagesKHR.
    
    // Transition frame history image to TRANSFER_DST for capture
    VkImageMemoryBarrier historyBarrier = {};
    historyBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    historyBarrier.srcAccessMask = 0;
    historyBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    historyBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    historyBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    historyBarrier.image = swapchainData->frameHistory[currentHistoryIdx];
    historyBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    historyBarrier.subresourceRange.levelCount = 1;
    historyBarrier.subresourceRange.layerCount = 1;
    
    swapchainData->device->CmdPipelineBarrier(
        swapchainData->commandBuffer,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &historyBarrier);
    
    // Copy current swapchain image to frame history
    // pPresentInfo->pImageIndices contains the indices of swapchain images being presented
    if (swapchainData->swapchainImages.size() > 0 && pPresentInfo->pImageIndices) {
        uint32_t swapchainImageIndex = pPresentInfo->pImageIndices[0];
        if (swapchainImageIndex < swapchainData->swapchainImages.size()) {
            VkImage swapchainImage = swapchainData->swapchainImages[swapchainImageIndex];
            
            // Transition swapchain image from PRESENT to TRANSFER_SRC
            VkImageMemoryBarrier swapchainBarrier = {};
            swapchainBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            swapchainBarrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
            swapchainBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            swapchainBarrier.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            swapchainBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            swapchainBarrier.image = swapchainImage;
            swapchainBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            swapchainBarrier.subresourceRange.levelCount = 1;
            swapchainBarrier.subresourceRange.layerCount = 1;
            
            swapchainData->device->CmdPipelineBarrier(
                swapchainData->commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                1, &swapchainBarrier);
            
            // Copy swapchain image to frame history
            VkImageCopy copyRegion = {};
            copyRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copyRegion.srcSubresource.layerCount = 1;
            copyRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copyRegion.dstSubresource.layerCount = 1;
            copyRegion.extent.width = swapchainData->extent.width;
            copyRegion.extent.height = swapchainData->extent.height;
            copyRegion.extent.depth = 1;
            
            // Copy from swapchain image to frame history buffer
            swapchainData->device->CmdCopyImage(
                swapchainData->commandBuffer,
                swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                swapchainData->frameHistory[currentHistoryIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1, &copyRegion);
            
            LOGI("GN-Framegen: Copied swapchain image %d to history[%d] (%dx%d)",
                 swapchainImageIndex, currentHistoryIdx,
                 swapchainData->extent.width, swapchainData->extent.height);
            
            // Transition swapchain image back to PRESENT
            swapchainBarrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            swapchainBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
            swapchainBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            swapchainBarrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            
            swapchainData->device->CmdPipelineBarrier(
                swapchainData->commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0,
                0, nullptr,
                0, nullptr,
                1, &swapchainBarrier);
        }
    }
    
    // Transition history buffer to SHADER_READ_ONLY for compute
    historyBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    historyBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    historyBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    historyBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    
    swapchainData->device->CmdPipelineBarrier(
        swapchainData->commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &historyBarrier);
    
    // If we have enough history, run frame generation
    uint32_t generatedFrameCount = 0;
    if (swapchainData->currentFrame >= 1 && swapchainData->frameGenerator) {
        // We have at least previous and current frames
        // GenerateFrames will add compute dispatches to the command buffer
        LOGI("GN-Framegen: Generating %d frames between history[%d] and history[%d]",
             swapchainData->generationCount, previousHistoryIdx, currentHistoryIdx);
        
        // Get generated images from FrameGenerator
        std::vector<VkImage> generatedImages(swapchainData->generationCount);
        
        generatedFrameCount = swapchainData->frameGenerator->GenerateFrames(
            swapchainData->commandBuffer,
            swapchainData->frameHistory[currentHistoryIdx],   // current
            swapchainData->frameHistory[previousHistoryIdx],    // previous
            generatedImages.data(),
            swapchainData->generationCount);
        
        if (generatedFrameCount > 0) {
            LOGI("GN-Framegen: Successfully generated %d frames", generatedFrameCount);
            
            // TODO: Present generated frames
            // Challenge: generatedImages are VkImage objects, not swapchain images
            // Options:
            // 1. Copy generated frames to additional swapchain images (need more swapchain images)
            // 2. Create a presentation mechanism for non-swapchain images
            // 3. Use a compositor that can display arbitrary images
            //
            // For now, we generate the frames but only present the real frame
            // Full implementation requires coordination with swapchain creation
            // to have enough images for real + generated frames
        } else {
            LOGW("GN-Framegen: Frame generation returned 0 frames");
        }
    }
    
    // End command buffer
    result = swapchainData->device->EndCommandBuffer(swapchainData->commandBuffer);
    if (result != VK_SUCCESS) {
        LOGE("GN-Framegen: Failed to end command buffer: %d", result);
        return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    }
    
    // Submit compute work
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &swapchainData->commandBuffer;
    
    // Reset fence before submission
    swapchainData->device->ResetFences(swapchainData->device->device, 1, &swapchainData->computeFence);
    
    result = swapchainData->device->QueueSubmit(queue, 1, &submitInfo, swapchainData->computeFence);
    if (result != VK_SUCCESS) {
        LOGE("GN-Framegen: Failed to submit compute work: %d", result);
        return swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    }
    
    // Wait for compute to complete before present
    // In production, use proper synchronization (semaphores) instead of blocking wait
    result = swapchainData->device->WaitForFences(swapchainData->device->device, 1, 
                                                   &swapchainData->computeFence, VK_TRUE, UINT64_MAX);
    if (result != VK_SUCCESS) {
        LOGE("GN-Framegen: Failed to wait for compute fence: %d", result);
        // Continue with present even if compute failed
    }
    
    // Now present the real frame
    result = swapchainData->device->QueuePresentKHR(queue, pPresentInfo);
    
    // Update frame counter for tracking previous/current frames
    swapchainData->currentFrame++;
    
    // Advance history index for circular buffer (swap current -> previous)
    swapchainData->historyIndex++;
    
    LOGI("GN-Framegen: Presented frame %d (captured history, generated %d frames, historyIndex=%d)",
         swapchainData->currentFrame, generatedFrameCount, swapchainData->historyIndex % SwapchainData::MAX_FRAME_HISTORY);
    
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
