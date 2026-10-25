# Development notes

## Build

Use JDK 17, Android SDK platform 34 and build-tools 34.0.0. The repository includes Gradle 8.7's wrapper. Configure `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir=...`.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Use `./gradlew.bat` in PowerShell. The debug APK is under `app/build/outputs/apk/debug/`. A normal `assembleRelease` produces an unsigned release build unless you configure your own signing. Do not commit keystores or passwords.

## Source map

| Path under `app/src/main/java/com/lumixmonitor` | Role |
| --- | --- |
| `ptp/` | PTP containers, bounded bulk reads and serialized USB transactions |
| `usb/` | Android USB discovery and permission handling |
| `wifi/` | Local camera address validation, HTTP control, advertised option catalog, SSDP discovery and capacity-one UDP/JPEG preview |
| `lumix/LumixCamera.kt`, `LumixRemote.kt` | Camera operations and extended property groups |
| `lumix/LiveviewFrame.kt`, `LiveviewLoop.kt`, `LatestPresentation.kt` | Frame parsing, acquisition, decoding and latest-frame presentation |
| `RemoteCameraController.kt` | Extended settings, telemetry, camera files, MF / AF control |
| `MonitorViewModel.kt` | Connection lifecycle, app state and high-level commands |
| `monitor/` | OpenGL rendering, LUT parser, scopes, demo and preview recorder |
| `ui/` | Compose layouts, setting wheels, native assist navigator and menus |
| `PreviewDiagnosisController.kt` | Read-only camera-state capture, original JPEG / PNG / JSON and ZIP export |

Tests include parser fixtures and numerical helpers under `app/src/test`. Fixtures are bounded property/status replies, not image frames or complete device-information dumps.

The Wi-Fi controller uses a separate serialized executor for HTTP control and heartbeat. UDP reception, decoding and presentation are independent with capacity-one hand-offs. `WifiSettingsCatalog` assigns opaque UI IDs to advertised HTTP tokens; it never reuses USB property encodings. Camera sockets bind to Android's Wi-Fi `Network` without changing the process default route. Initial access/stream setup is bounded; recovery respects playback and avoids repeatedly forcing a camera mode change. See [the Wi-Fi guide](WIFI.md) for unsupported operations and the difference between local app frame age and end-to-end latency.

## Protocol and transport

All PTP transactions share a fair lock. A request and its data / response complete before another command uses the channel. Interrupt events run separately with short read timeouts. Acquisition and decoding avoid an unbounded queue; stale frames can be dropped in favor of the latest frame.

The Android 13 freeze investigation found long pauses around frame-command writes while lock wait and UI delivery were short. Shortening interrupt read timeout removed the periodic stalls on the tested phone. The proposed JNI / GC interaction is an explanation of the observation, not a complete kernel or ART trace.

Use camera-provided property descriptors and validate data lengths before parsing. Unknown sentinels must remain unknown rather than becoming ISO, aperture or time values. The photo and movie setting namespaces use different operations; see [the protocol reference](lumix-protocol-reference.md).

Native MF Assist START must carry explicit coordinates. Do not restore the old FFFF-as-last-position assumption or rely on an immediate MOVE after START. The phone's remembered area is not a physical-camera readback.

## Debug helpers

`app/src/debug` contains an Android Canvas / GPU comparison activity and a Camera2 diagnostic capture helper. These are development tools, not prerequisites for normal camera monitoring. The debug manifest requests CAMERA permission; the main manifest does not.

The optional Camera2 helper stays inside the active monitor Activity to avoid switching to the stock phone camera and losing USB. It writes a private one-shot diagnostic image after a debug intent. No phone-camera images, personal reference footage, SDK files or device logs are bundled in the repository or APK assets.

## GitHub Actions

The build workflow runs JVM unit tests and creates a debug APK. It does not communicate with a physical camera and cannot establish USB stability, exposure accuracy or end-to-end latency. Its artifacts use an ephemeral CI debug key and are not a replacement for the separately checked release APK.

## Publication boundary

The project implements its own Kotlin transport and UI. Separately downloaded Panasonic SDK libraries, sample executables, headers and original manuals are not distributed here. No Panasonic binary is linked by Gradle. Preserve attribution when changing inherited MIT source. Add synthetic fixtures or narrowly scoped replies without device identifiers; do not commit raw debugging sessions.
