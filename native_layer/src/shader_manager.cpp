#include "shader_manager.hpp"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GN-Framegen", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GN-Framegen", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// TODO: Include embedded shader header when ready
// #include "shaders_embedded.hpp"

namespace GN {
namespace Framegen {

ShaderManager::ShaderManager(VkDevice device)
    : device(device)
{
}

ShaderManager::~ShaderManager() {
    // Destroy all shader modules
    for (auto& entry : shaders) {
        if (entry.module != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, entry.module, nullptr);
        }
    }
    shaders.clear();
}

VkResult ShaderManager::LoadAll() {
    LOGI("ShaderManager::LoadAll");
    
    // TODO: Load all embedded shaders
    // This will iterate through shaders_embedded.hpp and create VkShaderModules
    
    // Example of how it will work:
    // for (const auto& [name, data, size] : GameScopeVK::Shaders::SHADER_REGISTRY) {
    //     LoadShader(name.c_str(), 
    //                reinterpret_cast<const uint32_t*>(data.first), 
    //                data.second / sizeof(uint32_t));
    // }
    
    LOGI("Shader loading is stubbed - will use embedded shaders when integrated");
    
    return VK_SUCCESS;
}

VkResult ShaderManager::LoadShader(const char* name, const uint32_t* data, size_t wordCount) {
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = wordCount * sizeof(uint32_t);
    createInfo.pCode = data;
    
    VkShaderModule module;
    VkResult result = vkCreateShaderModule(device, &createInfo, nullptr, &module);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create shader module '%s': %d", name, result);
        return result;
    }
    
    ShaderEntry entry;
    entry.name = name;
    entry.module = module;
    entry.code.assign(data, data + wordCount);
    shaders.push_back(std::move(entry));
    
    LOGI("Loaded shader '%s': %zu words", name, wordCount);
    return VK_SUCCESS;
}

VkShaderModule ShaderManager::GetShader(const std::string& name) {
    for (const auto& entry : shaders) {
        if (entry.name == name) {
            return entry.module;
        }
    }
    
    LOGE("Shader '%s' not found", name.c_str());
    return VK_NULL_HANDLE;
}

} // namespace Framegen
} // namespace GN
