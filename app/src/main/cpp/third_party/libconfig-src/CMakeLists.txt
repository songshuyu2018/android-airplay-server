# Minimal libconfig build for the Android receiver: only the library, no
# examples/tests (their CMake pulls in host-only bits).
cmake_minimum_required(VERSION 3.22)
project(libconfig_min C)

add_library(config STATIC
    lib/libconfig.c
    lib/scanner.c
    lib/grammar.c
    lib/strbuf.c
    lib/util.c)
target_include_directories(config PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/lib
    ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(config PUBLIC LIBCONFIG_STATIC)
target_compile_options(config PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-implicit-fallthrough)
