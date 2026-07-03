cmake_minimum_required(VERSION 3.22.1)
project(gamenative_execshim C)

add_library(gamenative_execshim SHARED gamenative_execshim.c)

target_compile_options(gamenative_execshim PRIVATE
    -Wall
    -Wextra
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
    -ffunction-sections
    -fdata-sections
    -O2)

target_link_libraries(gamenative_execshim dl log)

target_link_options(gamenative_execshim PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384)
