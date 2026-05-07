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
find_package(Vulkan REQUIRED)

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
)

# Embedded shaders
if(EMBED_SHADERS)
    include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../extracted_shaders)
    add_compile_definitions(EMBED_SHADERS=1)
endif()

# Android-specific settings
if(BUILD_FOR_ANDROID)
    find_library(ANDROID_LIBRARY android)
    find_library(LOG_LIBRARY log)
    
    add_library(gn-framegen SHARED ${SOURCES} ${HEADERS})
    
    target_link_libraries(gn-framegen
        Vulkan::Vulkan
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
