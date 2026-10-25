# Compatibility and known issues

## Tested configuration

| Component | Tested |
| --- | --- |
| Camera | Panasonic DC-GH5, firmware 2.6 |
| Phone | OnePlus 8 Pro, Android 13 |
| Connection | USB Host / OTG, camera PC(Tether) |
| Stream | 1280×720 JPEG in the tested video mode, about 30 fps |
| Latest tested build | 0.5.3-gh5, versionCode 27 |

The app requires Android 8.0+ and USB Host support. Original-file saving and diagnostic ZIP export require Android 10+. The original upstream project targeted the S5; its results are not evidence that this fork has been tested on that camera. GH5 II, GH5S, other phones and other firmware versions need their own results.

## Experimental Wi-Fi branch

Version 0.6.0 adds a local HTTP / UDP transport for Remote Shooting & View, independent of USB PTP. The build and 111 JVM tests pass, and the APK is installed on the development phone. Direct GH5 connection delivered VGA 640×360 and QVGA 320×184 at approximately 30 fps in steady periods. ISO write/readback and restoration, REC / STOP and four orientations were checked. The phone's 5 GHz hotspot also carried video, but performed worse on the tested pair. Motion-associated receive stalls and recovery are still under investigation; shared-router operation, other hardware and every exposed setting remain unverified. These checks do not inherit USB's hardware results. See [Wi-Fi setup and limitations](WIFI.md).

## Validation levels

- **Hardware checked:** USB live view / recovery in tested interruptions; independent app overlays; native MF Assist area and ±1× controls; ISO / WB examples; selected video quality changes; REC / STOP; one original JPEG transfer; both screen orientations; diagnostic video capture / ZIP export.
- **Implemented, partial hardware coverage:** touch AF target selection, motorized focus hold / release, RW2 download, every allowed WB / profile / video choice, every Android version.
- **Not implemented or unresolved:** 4K USB preview; exact physical-camera MF-area readback; measured end-to-end latency; calibrated phone-to-LCD matching; the reported mode-dependent brightness difference.

## Practical limitations

USB tether preview is compressed and can be lower resolution than the camera's recording. The selected live-view mode name can describe a nominal 4:3 frame while the actual JPEG is cropped for the active video aspect. Read actual frame dimensions instead of assuming the mode label is the received image size.

Recovery cannot guarantee every USB state can be repaired without reconnecting. Backgrounding the application has interrupted sessions, and camera hangs needing a power cycle were reported during development. Keep the monitor foreground during a take and test your exact combination before relying on it.

Camera-native overlays may or may not be included in the preview depending on mode. App peaking and zebra are separate. If both are visible, disable either layer according to your preference.

MF Assist uses explicit phone-selected coordinates. The camera can acknowledge a MOVE immediately after START without visibly applying it; the app therefore sends the saved position directly in START. Its navigator does not claim to display the measured crop size or physical joystick state.

Scopes and display corrections operate on the preview, not RAW or guaranteed calibrated IRE. A brighter phone image alone does not prove an exposure error, full/limited-range mismatch or wrong gamma. Version 0.5.3 provides capture evidence; it does not automatically correct that issue.

## Report another configuration

Open an Issue with camera model, firmware, phone model, Android version, lens, cable / OTG setup, actual JPEG dimensions, selected quality and what worked. Separate camera SD recording from phone proxy recording. Include reproducible steps and redacted logs; never include your camera serial number or private photos by default.

