#include "shader_manager.hpp"
#include "shaders_embedded.hpp"

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
    LOGI("ShaderManager::LoadAll - loading %zu embedded shaders", GameScopeVK::Shaders::SHADER_REGISTRY.size());
    
    // Load all embedded shaders from shaders_embedded.hpp
    for (const auto& [name, data] : GameScopeVK::Shaders::SHADER_REGISTRY) {
        const uint8_t* shaderBytes = data.first;
        size_t byteSize = data.second;
        
        // Validate SPIR-V header
        if (byteSize < 20) {
            LOGE("Shader '%s' too small (%zu bytes), skipping", name.c_str(), byteSize);
            continue;
        }
        
        // Check magic number (SPIR-V: 0x07230203, little-endian as bytes)
        const uint32_t magic = 0x07230203;
        if (memcmp(shaderBytes, &magic, 4) != 0) {
            LOGE("Shader '%s' has invalid SPIR-V magic, skipping", name.c_str());
            continue;
        }
        
        // Load shader (size must be multiple of 4 for SPIR-V)
        if (byteSize % 4 != 0) {
            LOGE("Shader '%s' size (%zu) not word-aligned, skipping", name.c_str(), byteSize);
            continue;
        }
        
        VkResult result = LoadShader(name.c_str(),
                                     reinterpret_cast<const uint32_t*>(shaderBytes),
                                     byteSize / sizeof(uint32_t));
        if (result != VK_SUCCESS) {
            LOGE("Failed to load shader '%s': %d", name.c_str(), result);
            // Continue with other shaders
        }
    }
    
    LOGI("Shader loading complete: %zu/%zu shaders loaded", shaders.size(), 
         GameScopeVK::Shaders::SHADER_REGISTRY.size());
    
    return shaders.empty() ? VK_ERROR_INITIALIZATION_FAILED : VK_SUCCESS;
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
