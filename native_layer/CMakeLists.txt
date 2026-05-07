cmake_minimum_required(VERSION 3.16)
project(gn-framegen VERSION 1.0.0 LANGUAGES CXX)

# Options
option(BUILD_FOR_ANDROID "Build for Android (uses Android NDK)" ON)
option(EMBED_SHADERS "Embed SPIR-V shaders in binary" ON)

# C++ Standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Find Vulkan
if(BUILD_FOR_ANDROID)
    # Android NDK provides Vulkan via the toolchain
    # We'll use the headers from the NDK
    set(VULKAN_FOUND TRUE)
else()
    find_package(Vulkan REQUIRED)
endif()

# Source files
set(SOURCES
    src/layer.cpp
    src/framegen.cpp
    src/descriptor_manager.cpp
    src/shader_manager.cpp
)

# Headers
set(HEADERS
    src/layer.hpp
    src/framegen.hpp
    src/descriptor_manager.hpp
    src/shader_manager.hpp
    src/version.hpp
)

# Embedded shaders
if(EMBED_SHADERS)
    # shaders_embedded.hpp is in src/ directory
    include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src)
    add_compile_definitions(EMBED_SHADERS=1)
endif()

# Android NDK include paths for Vulkan and system headers
if(BUILD_FOR_ANDROID AND DEFINED ANDROID_NDK)
    # Add NDK system and Vulkan headers
    include_directories(
        ${ANDROID_NDK}/sources/android/native_app_glue
    )
endif()

# Android-specific settings
if(BUILD_FOR_ANDROID)
    find_library(ANDROID_LIBRARY android)
    find_library(LOG_LIBRARY log)
    
    # For Android NDK, Vulkan is available as part of the NDK toolchain
    # We link against vulkan and add the NDK include paths
    find_library(VULKAN_LIBRARY vulkan)
    
    add_library(gn-framegen SHARED ${SOURCES} ${HEADERS})
    
    target_link_libraries(gn-framegen
        ${VULKAN_LIBRARY}
        ${ANDROID_LIBRARY}
        ${LOG_LIBRARY}
    )
    
    # Android NDK specific compile flags
    target_compile_options(gn-framegen PRIVATE
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    )
    
    target_link_options(gn-framegen PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,--gc-sections
    )
else()
    # Desktop build (for testing)
    add_library(gn-framegen SHARED ${SOURCES} ${HEADERS})
    target_link_libraries(gn-framegen Vulkan::Vulkan)
endif()

# Layer name definition
target_compile_definitions(gn-framegen PRIVATE
    GN_LAYER_NAME="VK_LAYER_GN_gamescope_framegen"
)

# Install
install(TARGETS gn-framegen
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)

install(FILES VkLayer_GN_gamescope_framegen.json
    DESTINATION share/vulkan/explicit_layer.d
)
