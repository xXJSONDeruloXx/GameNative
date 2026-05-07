#pragma once

#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include <cstdint>

namespace GN {
namespace Framegen {

// Shader manager that loads embedded GameScopeVK SPIR-V shaders
class ShaderManager {
public:
    ShaderManager(VkDevice device);
    ~ShaderManager();
    
    // Load all embedded shaders
    VkResult LoadAll();
    
    // Get a specific shader module by name
    VkShaderModule GetShader(const std::string& name);
    
    // Shader names from GameScopeVK
    static constexpr const char* OPTICAL_FLOW_0 = "shader_000.spv";
    static constexpr const char* OPTICAL_FLOW_1 = "shader_001.spv";
    static constexpr const char* WARP_0 = "shader_003.spv";
    static constexpr const char* WARP_1 = "shader_004.spv";
    static constexpr const char* BLEND_0 = "shader_005.spv";
    static constexpr const char* BLEND_1 = "shader_006.spv";
    // ... additional shaders as needed

private:
    VkDevice device;
    
    struct ShaderEntry {
        std::string name;
        VkShaderModule module;
        std::vector<uint32_t> code; // SPIR-V words
    };
    
    std::vector<ShaderEntry> shaders;
    
    // Load shader from embedded data
    VkResult LoadShader(const char* name, const uint32_t* data, size_t size);
};

} // namespace Framegen
} // namespace GN
