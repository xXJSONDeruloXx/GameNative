cmake_minimum_required(VERSION 3.22.1)

project(gn_framegen LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_library(ANDROID_LIBRARY android)
find_library(LOG_LIBRARY log)
find_library(VULKAN_LIBRARY vulkan)

add_library(gn_framegen SHARED
    src/session.cpp
    src/jni_bridge.cpp
    src/vulkan/vk_impl.cpp
    src/framegen_context.cpp
    src/vk_layer/layer.cpp
)

target_include_directories(gn_framegen
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_compile_options(gn_framegen PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_libraries(gn_framegen
    ${ANDROID_LIBRARY}
    ${LOG_LIBRARY}
    ${VULKAN_LIBRARY}
)

target_link_options(gn_framegen PRIVATE
    -Wl,--gc-sections
)
