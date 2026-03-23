cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

#add_subdirectory(proot)
add_subdirectory(virglrenderer)
add_subdirectory(patchelf)

add_library(winlator SHARED
            winlator/drawable.c
            winlator/gpu_image.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)

add_library(fakeinput SHARED
            winlator/fakeinput.cpp)

add_library(evshim SHARED
            extras/evshim.c)

target_compile_features(fakeinput PRIVATE cxx_std_17)
target_compile_options(fakeinput PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(fakeinput
                      log
                      dl)

target_link_libraries(evshim
                      log
                      dl)
