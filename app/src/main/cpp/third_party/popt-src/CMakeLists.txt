cmake_minimum_required(VERSION 3.22)
project(popt C)
add_library(popt STATIC
    src/popt.c
    src/popthelp.c
    src/poptparse.c
    src/poptint.c
    src/lookup3.c)
# poptconfig.c is left out: it is the config-file loader, unused here.
target_include_directories(popt PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(popt PUBLIC
    _GNU_SOURCE
    PACKAGE="popt"
    HAVE_STPCPY=1
    HAVE_STRERROR=1
    HAVE_GLOB_H=1)
target_compile_options(popt PRIVATE -Wno-unused-parameter -Wno-sign-compare)
