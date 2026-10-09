// SPDX-License-Identifier: GPL-3.0-or-later
// eden-ps4: the game list shown before the emulator starts.

#pragma once

#include <string>
#include <vector>

/// Shows `names` (file names) with `initial` selected: Up/Down move (held: repeat), Cross picks.
/// Returns the chosen index, or -1 if the display could not be opened (nothing was shown).
int RunRomMenu(const std::vector<std::string>& names, int initial);
