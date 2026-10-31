cmake_minimum_required(VERSION 3.22)
project(popt C)
add_library(popt STATIC
    src/popt.c
    src/popthelp.c
    src/poptparse.c
    src/poptconfig.c
    src/poptint.c
    src/lookup3.c)
target_include_directories(popt PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(popt PRIVATE _GNU_SOURCE HAVE_GLOB_H=1)
