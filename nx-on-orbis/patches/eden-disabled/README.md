# Disabled patches

- `0034-dynarmic-x64-register-allocator-changes-from-Eden4.patch`: passed `dynarmic_tests` on a PC
  with the host features limited to the Jaguar, but on the console (tl7) Tomodachi Life never drew a
  frame: one emulated core spun at 100% in JIT code from boot, with `regalloc=off` too. Removed in tl8.
- `0035-PS4-staging-buffer-cap-and-GPU-memory-reclaim-before.patch`: tl8 (tl6 + 0035 only) crashed 6 s
  into the game with KernelCore's process-list mutex overwritten by text; tl7 (0034 + 0035) hung.
  tl6 without it reached the game's setup screens. Removed in tl9 until it is understood.
