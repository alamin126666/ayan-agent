# Third-party code in this repo

## Termux terminal libraries (Apache License 2.0)

The `terminal-emulator/` and `terminal-view/` modules are copied from the
Termux open-source project (https://github.com/termux/termux-app, version
0.118.0), copyright the Termux developers, licensed under the Apache License
2.0 (see `termux-app/LICENSE.md` in the Termux repository).

They provide the real terminal emulator (PTY subprocess via JNI, VT
emulation) and the Android terminal view that Ayan Agent uses for its
built-in Termux-like terminal. The code is used unmodified except for
build-system integration and one compile fix: `TerminalBitmap.java` uses
the literal `35` instead of `Build.VERSION_CODES.VANILLA_ICE_CREAM`
(API 35 is not in compileSdk 34).

## proot + talloc (GNU General Public License v2.0 or later)

The proot binary shipped in the APK (`libproot_exec.so`, built by
`tools/build-proot.sh` from the Termux fork of proot, v5.1.107.91, plus
talloc 2.4.2 statically linked) is GPL-2.0-or-later software, copyright
its respective authors (proot: STMicroelectronics and contributors;
talloc: Samba Team).

It is shipped as a separate executable that Ayan Agent invokes via `exec`
(mere aggregation — it is not linked into the app's code). Source code for
the exact versions used, including the build script and patches, is
available at:

- proot (Termux fork): https://github.com/termux/proot, tag v5.1.107.91
- talloc: https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz
- Build script and patches: `tools/build-proot.sh` and
  `tools/proot-thread-execve.patch` in this repository.

The full GPL-2.0 license text is in the proot repository (`COPYING`).
Patches applied by this project are marked `AYAN AGENT PATCH` in the
affected files.

## Termux bootstrap (downloaded at setup time, not shipped)

The Termux bootstrap zip (~30 MB per CPU arch) is downloaded by the app
from the termux-packages releases at first setup and is not included in
the APK. Its packages carry their own (mostly GPL/BSD/MIT) licenses.
