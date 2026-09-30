# ChatCapture for Android

ChatCapture captures copied WeChat conversations on Android as an ordered ZIP archive with available text and media, or as a plain-text file that joins text nodes and omits media payloads. Text already present in a message node, such as an image placeholder, is preserved.

## Requirements

- Android 7.0 (API 24) or later
- JDK 17 or later
- Android SDK Platform 35
- No third-party app libraries

## Build and install

Open this directory as a Gradle project in Android Studio, or run:

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on an Android device, select and copy messages in WeChat, return to ChatCapture, and tap one of the export buttons. The system share sheet then lets you choose where to send the saved file.

## Privacy

- The app reads the clipboard only after you tap an export button while it is open. It does not monitor clipboard changes or read the clipboard in the background.
- Capture and export happen on the device. The app requests no network or storage permissions and includes no analytics or third-party SDKs.
- On Android 10 and later, exports are saved under `Downloads/ChatCapture`. On earlier Android versions, the system file picker asks where to save them.
- A full ZIP may contain private conversation text and media. Review the file and destination before sharing it.

## License

GNU Affero General Public License v3.0 only. See [LICENSE](LICENSE).
