# Third-party code in this repo

## Termux terminal libraries (Apache License 2.0)

The `terminal-emulator/` and `terminal-view/` modules are copied from the
Termux open-source project (https://github.com/termux/termux-app, version
0.118.0), copyright the Termux developers, licensed under the Apache License
2.0 (see `termux-app/LICENSE.md` in the Termux repository).

They provide the real terminal emulator (PTY subprocess via JNI, VT
emulation) and the Android terminal view that Ayan Agent uses for its
built-in Termux-like terminal. The code is used unmodified except for
build-system integration.
