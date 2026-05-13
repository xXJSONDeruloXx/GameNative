// GN Framegen Vulkan implicit layer
// Intercepts vkCreateSwapchainKHR + vkQueuePresentKHR to inject generated frames.
// Image sharing between game device and framegen device is done via AHardwareBuffer.
// Enabled only when GN_FG_ENABLE=1 is set in the environment.

#include "../framegen_context.hpp"
#include "../logging.hpp"
#include "../vulkan/vk_types.hpp"

#include <vulkan/vulkan.h>
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <vulkan/vulkan_android.h>
#endif

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define GN_LAYER_NAME "VK_LAYER_GN_gamescope_framegen"
#ifdef __ANDROID__
#define GNFG_LAYER(...) __android_log_print(ANDROID_LOG_INFO, GN_LAYER_NAME, __VA_ARGS__)
#define GNFG_LAYER_E(...) __android_log_print(ANDROID_LOG_ERROR, GN_LAYER_NAME, __VA_ARGS__)
#else
#define GNFG_LAYER(...)
#define GNFG_LAYER_E(...)
#endif

namespace gnfg::layer {

// Minimal loader-link structs normally provided by vulkan/vk_layer.h. The
// Android NDK only ships vulkan_core.h, so define the ABI-compatible subset we
// need for vkCreateInstance/vkCreateDevice trampoline chaining.
typedef enum VkLayerFunction_ {
    VK_LAYER_LINK_INFO = 0,
    VK_LOADER_DATA_CALLBACK = 1,
} VkLayerFunction;

struct VkLayerInstanceLink_ {
    VkLayerInstanceLink_* pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr   pfnNextGetDeviceProcAddr;
};
using VkLayerInstanceLink = VkLayerInstanceLink_;

struct VkLayerInstanceCreateInfo {
    VkStructureType sType;
    const void* pNext;
    VkLayerFunction function;
    union { VkLayerInstanceLink* pLayerInfo; void* pfnSetInstanceLoaderData; } u;
};
using VkLayerDeviceCreateInfo = VkLayerInstanceCreateInfo;

// ─── Dispatch key helper ──────────────────────────────────────────────────────

static void* dispatchKey(void* h) { return *reinterpret_cast<void**>(h); }

// ─── Config ───────────────────────────────────────────────────────────────────

struct LayerConf {
    bool    enabled    = false;
    int     multiplier = 2;
    float   flowScale  = 0.6f;
    int     model      = 0;
};

static LayerConf readConf() {
    LayerConf c;
    const char* en = std::getenv("GN_FG_ENABLE");
    if (!en || en[0] != '1') return c;
    c.enabled = true;
    if (auto* v = std::getenv("GN_FG_MULTIPLIER"))
        c.multiplier = std::max(2, std::min(4, std::atoi(v)));
    if (auto* v = std::getenv("GN_FG_FLOW_SCALE"))
        c.flowScale = std::max(0.2f, std::min(1.0f, (float)std::atof(v)));
    if (auto* v = std::getenv("GN_FG_MODEL"))
        c.model = std::max(0, std::min(1, std::atoi(v)));
    return c;
}

// ─── Instance data ────────────────────────────────────────────────────────────

struct InstanceData {
    PFN_vkGetInstanceProcAddr  next = nullptr;
    PFN_vkDestroyInstance      destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
};

// ─── Device data ──────────────────────────────────────────────────────────────

struct DeviceData {
    VkInstance       instance  = VK_NULL_HANDLE;
    VkPhysicalDevice physical  = VK_NULL_HANDLE;
    uint32_t         queueFamilyIdx = 0;
    PFN_vkGetDeviceProcAddr        next                   = nullptr;
    PFN_vkDestroyDevice            destroyDevice          = nullptr;
    PFN_vkCreateSwapchainKHR       createSwapchain        = nullptr;
    PFN_vkDestroySwapchainKHR      destroySwapchain       = nullptr;
    PFN_vkGetSwapchainImagesKHR    getSwapchainImages     = nullptr;
    PFN_vkQueuePresentKHR          queuePresent           = nullptr;
    PFN_vkAcquireNextImageKHR      acquireNextImage       = nullptr;
    PFN_vkQueueSubmit              queueSubmit            = nullptr;
    PFN_vkDeviceWaitIdle           deviceWaitIdle         = nullptr;
    PFN_vkCreateCommandPool        createCmdPool          = nullptr;
    PFN_vkDestroyCommandPool       destroyCmdPool         = nullptr;
    PFN_vkAllocateCommandBuffers   allocCmdBufs           = nullptr;
    PFN_vkBeginCommandBuffer       beginCmdBuf            = nullptr;
    PFN_vkEndCommandBuffer         endCmdBuf              = nullptr;
    PFN_vkCmdPipelineBarrier       cmdPipelineBarrier     = nullptr;
    PFN_vkCmdBlitImage             cmdBlitImage           = nullptr;
    PFN_vkCreateImage              createImage            = nullptr;
    PFN_vkDestroyImage             destroyImage           = nullptr;
    PFN_vkAllocateMemory           allocMemory            = nullptr;
    PFN_vkFreeMemory               freeMemory             = nullptr;
    PFN_vkBindImageMemory          bindImageMemory        = nullptr;
    PFN_vkCreateImageView          createImageView        = nullptr;
    PFN_vkDestroyImageView         destroyImageView       = nullptr;
    PFN_vkCreateFence              createFence            = nullptr;
    PFN_vkDestroyFence             destroyFence           = nullptr;
    PFN_vkWaitForFences            waitForFences          = nullptr;
    PFN_vkResetFences              resetFences            = nullptr;
    PFN_vkCreateSemaphore          createSemaphore        = nullptr;
    PFN_vkDestroySemaphore         destroySemaphore       = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysDevMemProps = nullptr;
#ifdef __ANDROID__
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps = nullptr;
#endif
};

// ─── AHB-backed image on an external device ───────────────────────────────────

struct ExtImage {
    VkImage        img  = VK_NULL_HANDLE;
    VkDeviceMemory mem  = VK_NULL_HANDLE;
    VkImageView    view = VK_NULL_HANDLE;
};

#ifdef __ANDROID__
static ExtImage importAhb(const DeviceData& dd,
                          VkDevice device,
                          AHardwareBuffer* ahb,
                          VkExtent2D extent,
                          VkFormat format,
                          VkImageUsageFlags usage) {
    ExtImage out;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (!dd.getAhbProps || dd.getAhbProps(device, ahb, &ahbProps) != VK_SUCCESS)
        return out;

    VkExternalMemoryImageCreateInfo emici{};
    emici.sType      = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emici.handleTypes= VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ici{};
    ici.sType         = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.pNext         = &emici;
    ici.imageType     = VK_IMAGE_TYPE_2D;
    ici.format        = format;
    ici.extent        = {extent.width, extent.height, 1};
    ici.mipLevels     = 1;
    ici.arrayLayers   = 1;
    ici.samples       = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling        = VK_IMAGE_TILING_OPTIMAL;
    ici.usage         = usage;
    ici.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (dd.createImage(device, &ici, nullptr, &out.img) != VK_SUCCESS) return out;

    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = out.img;
    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType  = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.pNext  = &dedicated;
    importInfo.buffer = ahb;
    VkMemoryAllocateInfo mai{};
    mai.sType          = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext          = &importInfo;
    mai.allocationSize = ahbProps.allocationSize;
    VkPhysicalDeviceMemoryProperties memProps{};
    dd.getPhysDevMemProps(dd.physical, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i)
        if (ahbProps.memoryTypeBits & (1u << i)) { mai.memoryTypeIndex = i; break; }

    if (dd.allocMemory(device, &mai, nullptr, &out.mem) != VK_SUCCESS) {
        dd.destroyImage(device, out.img, nullptr);
        out.img = VK_NULL_HANDLE;
        return out;
    }
    dd.bindImageMemory(device, out.img, out.mem, 0);

    VkImageViewCreateInfo vci{};
    vci.sType                        = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.image                        = out.img;
    vci.viewType                     = VK_IMAGE_VIEW_TYPE_2D;
    vci.format                       = format;
    vci.components.r                 = VK_COMPONENT_SWIZZLE_IDENTITY;
    vci.components.g                 = VK_COMPONENT_SWIZZLE_IDENTITY;
    vci.components.b                 = VK_COMPONENT_SWIZZLE_IDENTITY;
    vci.components.a                 = VK_COMPONENT_SWIZZLE_IDENTITY;
    vci.subresourceRange.aspectMask  = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount  = 1;
    vci.subresourceRange.layerCount  = 1;
    dd.createImageView(device, &vci, nullptr, &out.view);
    return out;
}

static void freeExtImage(const DeviceData& dd, VkDevice device, ExtImage& img) {
    if (img.view) dd.destroyImageView(device, img.view, nullptr);
    if (img.mem)  dd.freeMemory(device, img.mem, nullptr);
    if (img.img)  dd.destroyImage(device, img.img, nullptr);
    img = {};
}
#endif

// ─── Swapchain state ──────────────────────────────────────────────────────────

struct SwapState {
    VkDevice   device = VK_NULL_HANDLE;
    VkExtent2D extent = {};
    VkFormat   format = VK_FORMAT_UNDEFINED;
    std::vector<VkImage> images;
    LayerConf  conf;

#ifdef __ANDROID__
    AHardwareBuffer* prevAhb = nullptr;
    AHardwareBuffer* currAhb = nullptr;
    std::vector<AHardwareBuffer*> outAhbs;

    ExtImage prevGameImg, currGameImg;
    std::vector<ExtImage> outGameImgs;

    std::unique_ptr<gn::framegen::FramegenContext> fgCtx;

    // Copy cmd infrastructure (on game's device)
    VkCommandPool   copyPool   = VK_NULL_HANDLE;
    VkCommandBuffer copyCmds[2]= {};
    VkFence         copyFences[2]={};
    VkSemaphore     genSems[4] = {};    // one per possible generated frame

    uint64_t frameCount = 0;
    bool     inPresent  = false;

    void cleanup(const DeviceData& dd, VkDevice dev) {
        for (auto& s : genSems) if (s) dd.destroySemaphore(dev, s, nullptr);
        for (auto& f : copyFences) if (f) dd.destroyFence(dev, f, nullptr);
        if (copyPool) dd.destroyCmdPool(dev, copyPool, nullptr);
        // Free AHB images on game's device
        freeExtImage(dd, dev, prevGameImg);
        freeExtImage(dd, dev, currGameImg);
        for (auto& img : outGameImgs) freeExtImage(dd, dev, img);
        // Destroy framegen context (frees its own device)
        if (fgCtx) { fgCtx->destroy(); fgCtx.reset(); }
        // Release AHBs
        if (prevAhb) AHardwareBuffer_release(prevAhb);
        if (currAhb) AHardwareBuffer_release(currAhb);
        for (auto* ahb : outAhbs) AHardwareBuffer_release(ahb);
        copyPool = VK_NULL_HANDLE;
    }
#endif
};

// ─── Global state ─────────────────────────────────────────────────────────────

static std::mutex g_mtx;
static std::unordered_map<void*, InstanceData> g_instances;
static std::unordered_map<void*, DeviceData>   g_devices;
static std::unordered_map<VkPhysicalDevice, VkInstance> g_physInstances;
static std::unordered_map<VkSwapchainKHR, SwapState> g_swapchains;
static std::unordered_map<VkSwapchainKHR, void*>     g_swapDevice;

// ─── Barrier helper ───────────────────────────────────────────────────────────

static void layerImageBarrier(const DeviceData& dd, VkCommandBuffer cmd,
        VkImage img,
        VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
        VkPipelineStageFlags dstStage, VkAccessFlags dstAccess,
        VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkImageMemoryBarrier b{};
    b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask       = srcAccess;
    b.dstAccessMask       = dstAccess;
    b.oldLayout           = oldLayout;
    b.newLayout           = newLayout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image               = img;
    b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    dd.cmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// ─── CreateInstance ───────────────────────────────────────────────────────────

VKAPI_ATTR VkResult VKAPI_CALL GNFramegen_CreateInstance(
        const VkInstanceCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkInstance* pInstance) {
    // Walk pNext for the layer link info
    auto* linkInfo = reinterpret_cast<const VkLayerInstanceCreateInfo*>(pCreateInfo->pNext);
    while (linkInfo && !(linkInfo->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO
                         && linkInfo->function == VK_LAYER_LINK_INFO)) {
        linkInfo = reinterpret_cast<const VkLayerInstanceCreateInfo*>(linkInfo->pNext);
    }
    if (!linkInfo) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGIPA = linkInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    // advance layer link
    const_cast<VkLayerInstanceCreateInfo*>(linkInfo)->u.pLayerInfo =
        linkInfo->u.pLayerInfo->pNext;

    auto* createInstance = reinterpret_cast<PFN_vkCreateInstance>(
        nextGIPA(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!createInstance) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = createInstance(pCreateInfo, pAllocator, pInstance);
    if (res != VK_SUCCESS) return res;

    InstanceData data;
    data.next            = nextGIPA;
    data.destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
        nextGIPA(*pInstance, "vkDestroyInstance"));
    data.enumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        nextGIPA(*pInstance, "vkEnumeratePhysicalDevices"));
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_instances[dispatchKey(*pInstance)] = data;
    }
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL GNFramegen_DestroyInstance(
        VkInstance instance, const VkAllocationCallbacks* pAllocator) {
    std::lock_guard<std::mutex> lk(g_mtx);
    auto it = g_instances.find(dispatchKey(instance));
    if (it != g_instances.end()) {
        for (auto pit = g_physInstances.begin(); pit != g_physInstances.end(); ) {
            pit = (pit->second == instance) ? g_physInstances.erase(pit) : std::next(pit);
        }
        it->second.destroyInstance(instance, pAllocator);
        g_instances.erase(it);
    }
}

VKAPI_ATTR VkResult VKAPI_CALL GNFramegen_EnumeratePhysicalDevices(
        VkInstance instance, uint32_t* pPhysicalDeviceCount,
        VkPhysicalDevice* pPhysicalDevices) {
    InstanceData id;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto it = g_instances.find(dispatchKey(instance));
        if (it == g_instances.end() || !it->second.enumeratePhysicalDevices)
            return VK_ERROR_INITIALIZATION_FAILED;
        id = it->second;
    }
    VkResult res = id.enumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices);
    if ((res == VK_SUCCESS || res == VK_INCOMPLETE) && pPhysicalDevices && pPhysicalDeviceCount) {
        std::lock_guard<std::mutex> lk(g_mtx);
        for (uint32_t i = 0; i < *pPhysicalDeviceCount; ++i)
            g_physInstances[pPhysicalDevices[i]] = instance;
    }
    return res;
}

// ─── CreateDevice ─────────────────────────────────────────────────────────────

static const char* kAhbExts[] = {
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_KHR_external_memory",
    "VK_KHR_sampler_ycbcr_conversion",
    "VK_KHR_dedicated_allocation",
    "VK_KHR_get_memory_requirements2",
    "VK_KHR_bind_memory2",
    "VK_KHR_maintenance1",
};

VKAPI_ATTR VkResult VKAPI_CALL GNFramegen_CreateDevice(
        VkPhysicalDevice physicalDevice,
        const VkDeviceCreateInfo* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkDevice* pDevice) {
    auto* linkInfo = reinterpret_cast<const VkLayerDeviceCreateInfo*>(pCreateInfo->pNext);
    while (linkInfo && !(linkInfo->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO
                         && linkInfo->function == VK_LAYER_LINK_INFO)) {
        linkInfo = reinterpret_cast<const VkLayerDeviceCreateInfo*>(linkInfo->pNext);
    }
    if (!linkInfo) return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr nextGIPA = linkInfo->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr   nextGDPA = linkInfo->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    const_cast<VkLayerDeviceCreateInfo*>(linkInfo)->u.pLayerInfo =
        linkInfo->u.pLayerInfo->pNext;

    // Inject AHB extensions if not present (needed for cross-device sharing)
    std::vector<const char*> exts(
        pCreateInfo->ppEnabledExtensionNames,
        pCreateInfo->ppEnabledExtensionNames + pCreateInfo->enabledExtensionCount);
    for (const char* e : kAhbExts) {
        bool found = false;
        for (const char* x : exts) if (std::strcmp(x, e) == 0) { found = true; break; }
        if (!found) exts.push_back(e);
    }
    VkDeviceCreateInfo dci = *pCreateInfo;
    dci.enabledExtensionCount   = static_cast<uint32_t>(exts.size());
    dci.ppEnabledExtensionNames = exts.data();

    // Look up the real instance that produced this physical device.
    VkInstance instance = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto pit = g_physInstances.find(physicalDevice);
        if (pit != g_physInstances.end()) instance = pit->second;
    }

    auto* createDevice = reinterpret_cast<PFN_vkCreateDevice>(
        nextGIPA(instance, "vkCreateDevice"));
    if (!createDevice) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = createDevice(physicalDevice, &dci, pAllocator, pDevice);
    if (res != VK_SUCCESS) return res;

    // Find compute/graphics queue family
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &qfCount, qfProps.data());
    uint32_t qf = 0;
    for (uint32_t i = 0; i < qfCount; ++i) {
        if (qfProps[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            qf = i; break;
        }
    }

    auto load = [&](const char* name) {
        return reinterpret_cast<PFN_vkVoidFunction>(nextGDPA(*pDevice, name));
    };

    DeviceData dd;
    dd.instance       = instance;
    dd.physical       = physicalDevice;
    dd.queueFamilyIdx = qf;
    dd.next                 = nextGDPA;
    dd.destroyDevice        = reinterpret_cast<PFN_vkDestroyDevice>(load("vkDestroyDevice"));
    dd.createSwapchain      = reinterpret_cast<PFN_vkCreateSwapchainKHR>(load("vkCreateSwapchainKHR"));
    dd.destroySwapchain     = reinterpret_cast<PFN_vkDestroySwapchainKHR>(load("vkDestroySwapchainKHR"));
    dd.getSwapchainImages   = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(load("vkGetSwapchainImagesKHR"));
    dd.queuePresent         = reinterpret_cast<PFN_vkQueuePresentKHR>(load("vkQueuePresentKHR"));
    dd.acquireNextImage     = reinterpret_cast<PFN_vkAcquireNextImageKHR>(load("vkAcquireNextImageKHR"));
    dd.queueSubmit          = reinterpret_cast<PFN_vkQueueSubmit>(load("vkQueueSubmit"));
    dd.deviceWaitIdle       = reinterpret_cast<PFN_vkDeviceWaitIdle>(load("vkDeviceWaitIdle"));
    dd.createCmdPool        = reinterpret_cast<PFN_vkCreateCommandPool>(load("vkCreateCommandPool"));
    dd.destroyCmdPool       = reinterpret_cast<PFN_vkDestroyCommandPool>(load("vkDestroyCommandPool"));
    dd.allocCmdBufs         = reinterpret_cast<PFN_vkAllocateCommandBuffers>(load("vkAllocateCommandBuffers"));
    dd.beginCmdBuf          = reinterpret_cast<PFN_vkBeginCommandBuffer>(load("vkBeginCommandBuffer"));
    dd.endCmdBuf            = reinterpret_cast<PFN_vkEndCommandBuffer>(load("vkEndCommandBuffer"));
    dd.cmdPipelineBarrier   = reinterpret_cast<PFN_vkCmdPipelineBarrier>(load("vkCmdPipelineBarrier"));
    dd.cmdBlitImage         = reinterpret_cast<PFN_vkCmdBlitImage>(load("vkCmdBlitImage"));
    dd.createImage          = reinterpret_cast<PFN_vkCreateImage>(load("vkCreateImage"));
    dd.destroyImage         = reinterpret_cast<PFN_vkDestroyImage>(load("vkDestroyImage"));
    dd.allocMemory          = reinterpret_cast<PFN_vkAllocateMemory>(load("vkAllocateMemory"));
    dd.freeMemory           = reinterpret_cast<PFN_vkFreeMemory>(load("vkFreeMemory"));
    dd.bindImageMemory      = reinterpret_cast<PFN_vkBindImageMemory>(load("vkBindImageMemory"));
    dd.createImageView      = reinterpret_cast<PFN_vkCreateImageView>(load("vkCreateImageView"));
    dd.destroyImageView     = reinterpret_cast<PFN_vkDestroyImageView>(load("vkDestroyImageView"));
    dd.createFence          = reinterpret_cast<PFN_vkCreateFence>(load("vkCreateFence"));
    dd.destroyFence         = reinterpret_cast<PFN_vkDestroyFence>(load("vkDestroyFence"));
    dd.waitForFences        = reinterpret_cast<PFN_vkWaitForFences>(load("vkWaitForFences"));
    dd.resetFences          = reinterpret_cast<PFN_vkResetFences>(load("vkResetFences"));
    dd.createSemaphore      = reinterpret_cast<PFN_vkCreateSemaphore>(load("vkCreateSemaphore"));
    dd.destroySemaphore     = reinterpret_cast<PFN_vkDestroySemaphore>(load("vkDestroySemaphore"));
    dd.getPhysDevMemProps   = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
        nextGIPA(instance, "vkGetPhysicalDeviceMemoryProperties"));
#ifdef __ANDROID__
    dd.getAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        load("vkGetAndroidHardwareBufferPropertiesANDROID"));
#endif
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_devices[dispatchKey(*pDevice)] = dd;
    }
    GNFG_LAYER("Device created, queueFamily=%u", qf);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL GNFramegen_DestroyDevice(
        VkDevice device, const VkAllocationCallbacks* pAllocator) {
    std::lock_guard<std::mutex> lk(g_mtx);
    auto it = g_devices.find(dispatchKey(device));
    if (it != g_devices.end()) {
        it->second.destroyDevice(device, pAllocator);
        g_devices.erase(it);
    }
}

// ─── CreateSwapchainKHR ───────────────────────────────────────────────────────

VKAPI_ATTR VkResult VKAPI_CALL GNFramegen_CreateSwapchainKHR(
        VkDevice device,
        const VkSwapchainCreateInfoKHR* pCreateInfo,
        const VkAllocationCallbacks* pAllocator,
        VkSwapchainKHR* pSwapchain) {
    LayerConf conf = readConf();

    DeviceData dd;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto it = g_devices.find(dispatchKey(device));
        if (it == g_devices.end()) return VK_ERROR_INITIALIZATION_FAILED;
        dd = it->second;
    }

    // Modify swapchain: add transfer usage, request extra images for generated frames
    VkSwapchainCreateInfoKHR ci = *pCreateInfo;
    ci.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (conf.enabled)
        ci.minImageCount = std::max(ci.minImageCount, static_cast<uint32_t>(conf.multiplier + 1));

    // Retire old swapchain state if recreating
    if (pCreateInfo->oldSwapchain) {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto old = g_swapchains.find(pCreateInfo->oldSwapchain);
        if (old != g_swapchains.end()) old->second.cleanup(dd, old->second.device);
        g_swapchains.erase(pCreateInfo->oldSwapchain);
        g_swapDevice.erase(pCreateInfo->oldSwapchain);
    }

    VkResult res = dd.createSwapchain(device, &ci, pAllocator, pSwapchain);
    if (res != VK_SUCCESS) return res;

    // Get swapchain images
    uint32_t imgCount = 0;
    dd.getSwapchainImages(device, *pSwapchain, &imgCount, nullptr);
    std::vector<VkImage> imgs(imgCount);
    dd.getSwapchainImages(device, *pSwapchain, &imgCount, imgs.data());

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_swapDevice[*pSwapchain] = dispatchKey(device);
    }

    if (!conf.enabled) {
        GNFG_LAYER("Layer disabled (GN_FG_ENABLE not set)");
        std::lock_guard<std::mutex> lk(g_mtx);
        SwapState st; st.device = device; st.extent = ci.imageExtent;
        st.format = ci.imageFormat; st.images = imgs; st.conf = conf;
        g_swapchains[*pSwapchain] = std::move(st);
        return VK_SUCCESS;
    }

#ifdef __ANDROID__
    SwapState st;
    st.device = device;
    st.extent = ci.imageExtent;
    st.format = ci.imageFormat;
    st.images = imgs;
    st.conf   = conf;

    try {
        const uint32_t W = ci.imageExtent.width, H = ci.imageExtent.height;
        const int N = conf.multiplier - 1;

        // Allocate AHBs
        auto allocAhb = [&](AHardwareBuffer** out) {
            AHardwareBuffer_Desc d{};
            d.width  = W; d.height = H; d.layers = 1;
            d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            d.usage  = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
                     | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
            AHardwareBuffer_allocate(&d, out);
        };
        allocAhb(&st.prevAhb);
        allocAhb(&st.currAhb);
        for (int k = 0; k < N; ++k) {
            AHardwareBuffer* o = nullptr; allocAhb(&o);
            st.outAhbs.push_back(o);
        }

        // Import AHBs on game's device (for copy ops)
        const VkImageUsageFlags copyUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                           | VK_IMAGE_USAGE_SAMPLED_BIT
                                           | VK_IMAGE_USAGE_STORAGE_BIT;
        const VkImageUsageFlags outUsage  = VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                           | VK_IMAGE_USAGE_SAMPLED_BIT
                                           | VK_IMAGE_USAGE_STORAGE_BIT;
        st.prevGameImg = importAhb(dd, device, st.prevAhb, {W,H}, VK_FORMAT_R8G8B8A8_UNORM, copyUsage);
        st.currGameImg = importAhb(dd, device, st.currAhb, {W,H}, VK_FORMAT_R8G8B8A8_UNORM, copyUsage);
        for (int k = 0; k < N; ++k)
            st.outGameImgs.push_back(importAhb(dd, device, st.outAhbs[size_t(k)], {W,H}, VK_FORMAT_R8G8B8A8_UNORM, outUsage));

        // Create copy command pool
        VkCommandPoolCreateInfo cpci{};
        cpci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cpci.queueFamilyIndex = dd.queueFamilyIdx;
        cpci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        dd.createCmdPool(device, &cpci, nullptr, &st.copyPool);

        VkCommandBufferAllocateInfo cbai{};
        cbai.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbai.commandPool        = st.copyPool;
        cbai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = 2;
        dd.allocCmdBufs(device, &cbai, st.copyCmds);

        VkFenceCreateInfo fci{};
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        dd.createFence(device, &fci, nullptr, &st.copyFences[0]);
        dd.createFence(device, &fci, nullptr, &st.copyFences[1]);

        VkSemaphoreCreateInfo sci{};
        sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        for (int k = 0; k < N && k < 4; ++k)
            dd.createSemaphore(device, &sci, nullptr, &st.genSems[k]);

        // Create FramegenContext (uses its own device, imports AHBs)
        gn::framegen::Config cfg;
        cfg.width      = W; cfg.height = H;
        cfg.multiplier = static_cast<uint32_t>(conf.multiplier);
        cfg.flowScale  = conf.flowScale;
        cfg.model      = static_cast<uint32_t>(conf.model);
        st.fgCtx = gn::framegen::FramegenContext::create(
            st.prevAhb, st.currAhb, st.outAhbs, {W,H}, VK_FORMAT_R8G8B8A8_UNORM, cfg);

        if (!st.fgCtx) {
            GNFG_LAYER_E("FramegenContext creation failed — layer will passthrough");
        } else {
            GNFG_LAYER("SwapchainState ready: %ux%u mult=%d shaders=embedded", W, H, N+1);
        }
    } catch (...) {
        GNFG_LAYER_E("Exception in CreateSwapchainKHR setup — layer will passthrough");
        st.fgCtx.reset();
    }

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_swapchains[*pSwapchain] = std::move(st);
    }
#endif
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL GNFramegen_DestroySwapchainKHR(
        VkDevice device,
        VkSwapchainKHR swapchain,
        const VkAllocationCallbacks* pAllocator) {
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto dit = g_devices.find(dispatchKey(device));
        auto sit = g_swapchains.find(swapchain);
        if (dit != g_devices.end() && sit != g_swapchains.end())
            sit->second.cleanup(dit->second, sit->second.device);
        g_swapchains.erase(swapchain);
        g_swapDevice.erase(swapchain);
    }
    auto it = g_devices.find(dispatchKey(device));
    if (it != g_devices.end())
        it->second.destroySwapchain(device, swapchain, pAllocator);
}

// ─── QueuePresentKHR ──────────────────────────────────────────────────────────

VKAPI_ATTR VkResult VKAPI_CALL GNFramegen_QueuePresentKHR(
        VkQueue queue,
        const VkPresentInfoKHR* pPresentInfo) {
    if (pPresentInfo->swapchainCount == 0)
        goto passthrough;

    {
        void* devKey = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            auto sit = g_swapDevice.find(pPresentInfo->pSwapchains[0]);
            if (sit != g_swapDevice.end()) devKey = sit->second;
        }
        if (!devKey) goto passthrough;

        DeviceData dd;
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            auto dit = g_devices.find(devKey);
            if (dit == g_devices.end()) goto passthrough;
            dd = dit->second;
        }

        VkSwapchainKHR swapchain = pPresentInfo->pSwapchains[0];
        uint32_t imgIdx = pPresentInfo->pImageIndices[0];

        SwapState* stPtr = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            auto it = g_swapchains.find(swapchain);
            if (it == g_swapchains.end()) goto passthrough;
            stPtr = &it->second;
        }
        SwapState& st = *stPtr;

        // Recursion guard
        if (st.inPresent) goto passthrough;

#ifdef __ANDROID__
        if (!st.fgCtx || !st.conf.enabled) goto passthrough;

        st.inPresent = true;
        const uint64_t fi = st.frameCount & 1u;
        VkDevice device = st.device;
        const int N = st.conf.multiplier - 1;

        // --- Step 1: Copy swapchain image → currGameImg (then swap prev/curr) ---
        dd.waitForFences(device, 1, &st.copyFences[fi], VK_TRUE, UINT64_MAX);
        dd.resetFences(device, 1, &st.copyFences[fi]);

        vkResetCommandBuffer(st.copyCmds[fi], 0);
        VkCommandBufferBeginInfo cbi{};
        cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        dd.beginCmdBuf(st.copyCmds[fi], &cbi);

        VkCommandBuffer cmd = st.copyCmds[fi];

        // Transition swapchain[imgIdx] → TRANSFER_SRC
        layerImageBarrier(dd, cmd, st.images[imgIdx],
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        // Transition currGameImg → TRANSFER_DST
        layerImageBarrier(dd, cmd, st.currGameImg.img,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[1]  = {(int32_t)st.extent.width, (int32_t)st.extent.height, 1};
        blit.dstSubresource = blit.srcSubresource;
        blit.dstOffsets[1]  = blit.srcOffsets[1];
        dd.cmdBlitImage(cmd, st.images[imgIdx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            st.currGameImg.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &blit, VK_FILTER_NEAREST);

        // Restore swapchain[imgIdx] → PRESENT_SRC
        layerImageBarrier(dd, cmd, st.images[imgIdx],
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        // Restore currGameImg → GENERAL
        layerImageBarrier(dd, cmd, st.currGameImg.img,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);

        dd.endCmdBuf(cmd);

        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers    = &cmd;
        dd.queueSubmit(queue, 1, &si, st.copyFences[fi]);

        // Wait for copy to complete before framegen reads the AHB
        dd.waitForFences(device, 1, &st.copyFences[fi], VK_TRUE, UINT64_MAX);

        // --- Step 2: Run framegen ---
        if (st.frameCount > 0) {
            st.fgCtx->present(st.prevAhb, st.currAhb);
            st.fgCtx->waitIdle();

            // --- Step 3: Inject generated frames ---
            for (int k = 0; k < N; ++k) {
                // Try to acquire a free swapchain image (non-blocking)
                uint32_t genIdx = UINT32_MAX;
                VkResult acqRes = dd.acquireNextImage(device, swapchain, 0,
                    st.genSems[k], VK_NULL_HANDLE, &genIdx);
                if (acqRes != VK_SUCCESS && acqRes != VK_SUBOPTIMAL_KHR) continue;

                // Blit output AHB → swapchain[genIdx]
                VkCommandBuffer genCmd = st.copyCmds[fi ^ 1];
                vkResetCommandBuffer(genCmd, 0);
                dd.beginCmdBuf(genCmd, &cbi);

                layerImageBarrier(dd, genCmd, st.outGameImgs[size_t(k)].img,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                layerImageBarrier(dd, genCmd, st.images[genIdx],
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                dd.cmdBlitImage(genCmd,
                    st.outGameImgs[size_t(k)].img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    st.images[genIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1, &blit, VK_FILTER_LINEAR);

                layerImageBarrier(dd, genCmd, st.outGameImgs[size_t(k)].img,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
                layerImageBarrier(dd, genCmd, st.images[genIdx],
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

                dd.endCmdBuf(genCmd);

                VkSemaphore waitSems[]   = { st.genSems[k] };
                VkSemaphore signalSems[] = { st.genSems[k] };
                VkPipelineStageFlags waitMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
                VkSubmitInfo gsi{};
                gsi.sType                = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                gsi.waitSemaphoreCount   = 1;
                gsi.pWaitSemaphores      = waitSems;
                gsi.pWaitDstStageMask    = &waitMask;
                gsi.commandBufferCount   = 1;
                gsi.pCommandBuffers      = &genCmd;
                gsi.signalSemaphoreCount = 1;
                gsi.pSignalSemaphores    = signalSems;
                dd.queueSubmit(queue, 1, &gsi, VK_NULL_HANDLE);

                // Present the generated frame
                VkPresentInfoKHR genPresent{};
                genPresent.sType              = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
                genPresent.waitSemaphoreCount = 1;
                genPresent.pWaitSemaphores    = signalSems;
                genPresent.swapchainCount     = 1;
                genPresent.pSwapchains        = &swapchain;
                genPresent.pImageIndices      = &genIdx;
                st.inPresent = true;   // recursion guard already set
                dd.queuePresent(queue, &genPresent);
            }
        }

        // Swap prev/curr for next cycle (we rebind via swapFrameInputs on next pass)
        std::swap(st.prevAhb, st.currAhb);
        std::swap(st.prevGameImg, st.currGameImg);
        st.fgCtx->updateConfig(st.fgCtx->valid()
            ? gn::framegen::Config{st.extent.width, st.extent.height,
                uint32_t(st.conf.multiplier), st.conf.flowScale, uint32_t(st.conf.model)}
            : gn::framegen::Config{});

        st.frameCount++;
        st.inPresent = false;
#endif
    }

passthrough:
    // Pass through the original present
    void* devKey = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        if (pPresentInfo->swapchainCount > 0) {
            auto sit = g_swapDevice.find(pPresentInfo->pSwapchains[0]);
            if (sit != g_swapDevice.end()) devKey = sit->second;
        }
    }
    if (devKey) {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto dit = g_devices.find(devKey);
        if (dit != g_devices.end())
            return dit->second.queuePresent(queue, pPresentInfo);
    }
    return vkQueuePresentKHR(queue, pPresentInfo);
}

// ─── GetProcAddr entry points ─────────────────────────────────────────────────

#define HOOK(fn) if (std::strcmp(name, #fn) == 0) return reinterpret_cast<PFN_vkVoidFunction>(GNFramegen_##fn)

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GNFramegen_GetDeviceProcAddr(
        VkDevice device, const char* name) {
    HOOK(DestroyDevice);
    HOOK(CreateSwapchainKHR);
    HOOK(DestroySwapchainKHR);
    HOOK(QueuePresentKHR);

    std::lock_guard<std::mutex> lk(g_mtx);
    auto it = g_devices.find(dispatchKey(device));
    if (it != g_devices.end() && it->second.next)
        return it->second.next(device, name);
    return nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL GNFramegen_GetInstanceProcAddr(
        VkInstance instance, const char* name) {
    HOOK(CreateInstance);
    HOOK(DestroyInstance);
    HOOK(EnumeratePhysicalDevices);
    HOOK(CreateDevice);
    HOOK(DestroyDevice);
    HOOK(CreateSwapchainKHR);
    HOOK(DestroySwapchainKHR);
    HOOK(QueuePresentKHR);
    if (std::strcmp(name, "vkGetDeviceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GNFramegen_GetDeviceProcAddr);
    if (std::strcmp(name, "vkGetInstanceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(GNFramegen_GetInstanceProcAddr);

    if (instance == VK_NULL_HANDLE) return nullptr;
    std::lock_guard<std::mutex> lk(g_mtx);
    auto it = g_instances.find(dispatchKey(instance));
    if (it != g_instances.end() && it->second.next)
        return it->second.next(instance, name);
    return nullptr;
}

#undef HOOK

} // namespace gnfg::layer

// ─── C-linkage exports (referenced by manifest JSON) ─────────────────────────

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
GNFramegen_GetInstanceProcAddr(VkInstance inst, const char* name) {
    return gnfg::layer::GNFramegen_GetInstanceProcAddr(inst, name);
}

extern "C" VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
GNFramegen_GetDeviceProcAddr(VkDevice dev, const char* name) {
    return gnfg::layer::GNFramegen_GetDeviceProcAddr(dev, name);
}
