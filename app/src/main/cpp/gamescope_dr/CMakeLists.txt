cmake_minimum_required(VERSION 3.22.1)
project(GamescopeDR C CXX)

add_library(gamescope_dr SHARED direct_rendering_server.cpp)

target_link_libraries(gamescope_dr
    log
    android
)
