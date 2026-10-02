# Lost Phone Finder v0.1.3

Renamed launcher/application label, main header, settings footer, permission explanation, active-search notification titles/channel display name, and default export filenames. The app name is a shared Android string resource. Version code is 4.

The package remains org.blefinder; signing certificates, database filename, stored preferences and session data are unchanged. Install the same debug/release variant as before to update in place. Scanning, grouping, sound and UI controls are unchanged.

Files changed: AndroidManifest.xml, res/values/strings.xml, SearchApp.kt, SettingsScreen.kt, SearchService.kt, MainActivity.kt, app/build.gradle.kts, README.md.

Validation: 64 debug and 64 release tests passed. Both full lint checks have zero errors and seven existing warnings. APK launcher labels/version verified with aapt2; runtime-class audit, signature verification and 16 KB alignment passed for both variants. Signed APKs and verification logs are in build/releases/v0.1.3/.
