# Minimal libsodium build (the 1.0.20 release tarball ships autotools only).
# Only the primitives pair_ap needs are used, but the whole library is cheap.
cmake_minimum_required(VERSION 3.22)
project(sodium_min C)

file(GLOB_RECURSE SODIUM_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/libsodium/*.c")
add_library(sodium STATIC ${SODIUM_SOURCES})
target_include_directories(sodium PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/libsodium/include)
target_compile_definitions(sodium PUBLIC SODIUM_STATIC=1)
target_compile_options(sodium PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-macro-redefined)
