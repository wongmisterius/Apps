// The overlay's crash handlers (SIGABRT and std::terminate report through orbis_log) live behind a
// C++ name, so the C probe reaches them through this one function.
#include <orbis_boot.h>

extern "C" void probe_install_orbis_handlers(void)
{
    orbis::installCrashHandlers();
}
