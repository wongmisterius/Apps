# eden-probe: one PS4 package that measures whether a Switch emulator can run here.
#   cmake -S . -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE=<orbis-sdk>/toolchain/orbis-sdk.cmake
cmake_minimum_required(VERSION 3.16)
project(eden_probe C CXX)

if(NOT DEFINED ORBIS_COMPAT_DIR OR NOT PS4)
  message(FATAL_ERROR "configure with -DCMAKE_TOOLCHAIN_FILE=<orbis-sdk>/toolchain/orbis-sdk.cmake")
endif()
if(NOT ORBIS_MESA_SRC)
  set(ORBIS_MESA_SRC "$ENV{ORBIS_MESA_SRC}")
endif()

find_program(GLSLANG NAMES glslangValidator glslang REQUIRED)
function(probe_spv file var)
  add_custom_command(
    OUTPUT  "${CMAKE_CURRENT_BINARY_DIR}/${var}.h"
    COMMAND "${GLSLANG}" -V "${CMAKE_CURRENT_LIST_DIR}/${file}" --vn "${var}" -o "${CMAKE_CURRENT_BINARY_DIR}/${var}.h"
    DEPENDS "${CMAKE_CURRENT_LIST_DIR}/${file}")
endfunction()
probe_spv(tri.vert tri_vert_spv)
probe_spv(tri.frag tri_frag_spv)
probe_spv(fill.comp fill_comp_spv)

add_executable(eden_probe probe.c boot.cpp
  "${CMAKE_CURRENT_BINARY_DIR}/tri_vert_spv.h"
  "${CMAKE_CURRENT_BINARY_DIR}/tri_frag_spv.h"
  "${CMAKE_CURRENT_BINARY_DIR}/fill_comp_spv.h")
target_include_directories(eden_probe PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_options(eden_probe PRIVATE -O2 -fno-omit-frame-pointer -gline-tables-only)

add_subdirectory("${ORBIS_COMPAT_DIR}/vkloader" ps4-vkloader)
target_link_libraries(eden_probe PRIVATE ps4-vkloader)

orbis_compat_verify()
ps4_create_eboot(eden_probe)
