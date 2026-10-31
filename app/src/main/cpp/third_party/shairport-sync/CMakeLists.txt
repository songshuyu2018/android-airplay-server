# shairport-sync (AirPlay 2 receiver) built as a static library for the app.
# Only the pieces needed for an in-process receiver are compiled: no config
# file (libconfig) and no command line (popt) support, so those two
# dependencies are replaced by stubs in stub/.
cmake_minimum_required(VERSION 3.22)

file(GLOB_RECURSE SPS_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.c")
# audio backends we do not use
list(REMOVE_ITEM SPS_SOURCES
     "${CMAKE_CURRENT_SOURCE_DIR}/audio_stdout.c"
     "${CMAKE_CURRENT_SOURCE_DIR}/audio_pipe.c"
     "${CMAKE_CURRENT_SOURCE_DIR}/dacp.c")   # needs libtinyhttp; DACP client is off
# DBus/Mpris test clients and the glib helper they need
list(REMOVE_ITEM SPS_SOURCES
     "${CMAKE_CURRENT_SOURCE_DIR}/shairport-sync-dbus-test-client.c"
     "${CMAKE_CURRENT_SOURCE_DIR}/shairport-sync-mpris-test-client.c"
     "${CMAKE_CURRENT_SOURCE_DIR}/utilities/g_variant_pretty_print.c")

add_library(shairport_sync STATIC ${SPS_SOURCES})

target_include_directories(shairport_sync PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/utilities
    ${CMAKE_CURRENT_SOURCE_DIR}/metadata
    ${CMAKE_CURRENT_SOURCE_DIR}/pair_ap
    ${CMAKE_CURRENT_SOURCE_DIR}/android-stubs
    ${CMAKE_CURRENT_SOURCE_DIR}/../libplist/include)

# shairport-sync relies on these being force-included (autotools passes
# --include=config.h --include=utilities/debug.h)
target_compile_options(shairport_sync PRIVATE
    "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/config.h"
    "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/utilities/debug.h"
    "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/sps_android_compat.h"
    -Wno-multichar -Wno-unused-parameter -Wno-format)

target_compile_definitions(shairport_sync PRIVATE
    _GNU_SOURCE
    SYSCONFDIR="/data/local/tmp")

target_link_libraries(shairport_sync PUBLIC config popt sodium)
