# Vuzix Blade Hello World (Android)

This workspace contains a minimal Android app (Java) that displays:

`Hello, Vuzix Blade`

It is designed as a starting point for Vuzix Blade AR glasses app development.

## 1) Prerequisites (macOS)

- Android Studio (recommended by Vuzix)
- JDK 17+
- Android SDK Platform 34
- Android SDK Build-Tools 34.x
- Android Platform-Tools (`adb`)

## 2) Configure Android SDK

If Android Studio is already installed, install SDK packages from SDK Manager.

For command-line setup, run:

```bash
./scripts/setup-android-sdk-macos.sh
```

Then source shell config (or open a new terminal):

```bash
source ~/.zshrc
```

## 3) Build the app

```bash
./gradlew assembleDebug
```

## 4) Connect and deploy to Vuzix Blade

1. Enable Developer Options and USB debugging on the device.
2. Connect over USB.
3. Verify connection:

```bash
adb devices
```

4. Install debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 5) Run from Android Studio

- Open this workspace root in Android Studio.
- Let Android Studio finish Gradle sync.
- Select the connected Vuzix device and run the `app` configuration.

## Notes for Vuzix UX

- Keep interactions simple and hands-free where possible.
- Use lightweight UI and large readable text.
- For production use, consider Vuzix SDKs (Speech, Barcode, Connectivity) from Vuzix developer resources.