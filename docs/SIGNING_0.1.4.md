# Shared signing in v0.1.4

Both debug and release field-test APKs now use the original field-test/debug certificate:

`29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`

The package remains `org.blefinder`; both APKs have version code 5. Either can replace an installation using that certificate without uninstalling or resetting stored data. An older release installation signed with the separate legacy release certificate cannot be updated with this key; export sessions before a one-time reinstall. The legacy key is retained locally for compatibility builds.

Private signing configuration in `.release-signing/release.properties` applies to both variants. An explicit `fieldTestKeystore` override also applies to both variants. No private key or signing properties are committed. A clean CI checkout uses the standard development debug certificate and builds an unsigned release; those CI artifacts do not replace the locally signed field-test APKs.

Debug enables the debugger and synthetic simulation. Release disables debugging and simulation. Neither build currently shrinks/obfuscates the code. Scanning, audio, storage and the real-device UI are the same.

Verification: 64 tests per variant (128 total), no failures/errors/skips. Full lint: zero errors, seven existing warnings per variant. Both APK signatures verified and their certificate digests matched the original field-test certificate; package/version/label/debuggable flag verified from packaged APKs. Runtime audit passed for all 67 selected artifacts and 12,137 dependency classes. Both APKs passed 16 KB alignment checks. APKs and detailed logs remain untracked under build/releases/v0.1.4/.
