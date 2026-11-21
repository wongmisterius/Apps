# Disabled patches

- `0034-dynarmic-x64-register-allocator-changes-from-Eden4.patch`: passed `dynarmic_tests` on a PC
  with the host features limited to the Jaguar, but on the console (tl7) Tomodachi Life never drew a
  frame: one emulated core spun at 100% in JIT code from boot, with `regalloc=off` too. Removed in tl8.
