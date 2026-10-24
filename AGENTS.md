# 2SUB TV: instructions for coding and installation agents

The public repository is https://github.com/zignifer/twosubstv. Read README.md and docs/INSTALL.md before helping someone install the app.

## Installing for a user

- A user's request to install/update authorizes the ordinary app installation. Ask for missing device information or required on-device pairing, not redundant confirmation of already authorized steps.
- Use a computer with access to the user's TV. Inspect `adb devices -l`; select the actual device explicitly. Never assume a historic LAN address or blindly act on multiple devices.
- Check Android version (API 26+), Android TV/Google TV compatibility, model and ABI. Keep an up-to-date System WebView. Use the signed universal APK from this repository's Releases and verify the hash in release.json. docs/INSTALL.md explains the process.
- Package ID: `app.twosub.tv`; launch component: `app.twosub.tv/.MainActivity`.
- Update with `adb install -r`. Never uninstall or clear app data to fix a signature mismatch: explain it and stop that update. Preserve session, history and preferences.
- Do not change the launcher, VPN, other installed applications, network settings or system settings as a side effect. Pair/debug only within the user's authorized scope. Never expose ADB to the internet.
- Login uses the ordinary 2SUB account flow. The user scans the QR on their TV and confirms from their phone. Do not request their password, session or payment details. Subscription payments belong on https://2sub.movie/prices/.
- Verify launch and remote navigation. Report what actually passed; package installation alone is not proof of login, playback or long-term stability.

## Developing and publishing

- Work inside this project. One product: Kotlin/Compose TV interface with the site's original player/session in WebView. No Google Play or product editions.
- Retain both subtitle languages and all four modes: both, English, Russian, off.
- WebView content is untrusted. Preserve origin-restricted bridge, command validation, top-level URL restrictions and certificate validation. Do not bypass membership, authorization, geographic restrictions or protected playback.
- Never commit cookies, credentials, account identifiers, pairing tokens/codes or signing keys. Keep raw device captures in ignored `private/`. Public login screenshots must contain only a clearly disclosed demo QR with no pairing credentials.
- Build instructions: docs/BUILD.md. Preserve the package ID and release key for updates. Release keys are private; use environment-based signing. Public APKs must be non-debuggable with WebView debugging disabled.
- `.local-signing/debug.keystore` belongs to the maintainer's local test installation. Do not replace it or delete that installation. It is not the public release key.
- Publication was explicitly authorized by the owner on 2026-10-24. Future routine development does not imply publishing: push/release when the user requests it.
- Run meaningful checks: Kotlin HistoryPolicy tests, adapter regression tests, release metadata/checksum checks and device verification appropriate to the change. Do not treat a script that merely loads as an executed test.
- Never overwrite machine/user JAVA_HOME, ANDROID_HOME or GRADLE_USER_HOME. `build.ps1` changes them only for its process and supports the maintainer's local tool bundle.

## Visual conventions

- Russian UI, Lucide icons, stable button/card size in every focus state.
- Readable TV type; consistent two-line title slots and aligned metadata in catalog cards.
- Posters keep their original aspect ratio. Preserve the site's EN/RU subtitle functionality with matching typography.
- Prefer evidence from actual screenshots over claims that a successful build proves UX.
