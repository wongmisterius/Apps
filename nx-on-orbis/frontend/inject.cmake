# eden-ps4: adds the PS4 frontend once Eden's own targets exist (passed to Eden's configure as
# -DCMAKE_PROJECT_yuzu_INCLUDE=<this file>), the same hook ProsperoEden uses. Deferred code may
# not add subdirectories, so the frontend's CMake is included in place.
set(EDEN_PS4_FRONTEND_DIR "${CMAKE_CURRENT_LIST_DIR}")
function(eden_ps4_add_frontend)
    include("${EDEN_PS4_FRONTEND_DIR}/frontend.cmake")
endfunction()
cmake_language(DEFER CALL eden_ps4_add_frontend)
