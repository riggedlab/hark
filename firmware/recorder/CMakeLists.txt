cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(recorder)

include(${CMAKE_CURRENT_SOURCE_DIR}/../lib/opus.cmake)

target_sources(app PRIVATE src/main.c src/audio.c src/ble.c src/battery.c src/agc.c)
target_link_libraries(app PRIVATE opus)
