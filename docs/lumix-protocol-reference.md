# Panasonic USB PTP: implementation notes

This English reference summarizes the operations used by the GH5 fork and supersedes the upstream S5-oriented conclusions where GH5 hardware showed different behavior. It is not a replacement for official camera documentation and does not claim model-independent support. The original upstream reference remains available in Git history.

## Sources and evidence

- Original MIT Android application: [velkilllmer/lumix-USB-tether-monitor-android](https://github.com/velkilllmer/lumix-USB-tether-monitor-android).
- MIT Panasonic PTP reference: [baku89/tethr](https://github.com/baku89/tethr).
- [Panasonic's LUMIX Remote Control Library page](https://av.jpn.support.panasonic.com/support/global/cs/soft/tool/sdk.html), consulted separately during development; its downloaded SDK and documents are not redistributed.
- Bounded property/status replies and physical checks on DC-GH5 firmware 2.6. See [validation](VALIDATION.md) for scope and gaps.

The application speaks USB PTP directly with Android USB Host APIs. It does not use Wi-Fi `cam.cgi`, UVC capture, a Panasonic DLL or an HDMI adapter. Do not mix USB property groups with the separate Wi-Fi API.

## Transactions

PTP command, data and response containers are length-checked and transaction IDs are verified. Camera operations share a serialized, fair transport lock. Data reads have explicit size/time bounds; an unsupported property or sentinel should not become a plausible UI value. See `ptp/PtpUsbTransport.kt` and `PtpBulkReader.kt` for the actual implementation.

The session opens standard PTP OpenSession before the Panasonic vendor session. USB events are read separately from the interrupt endpoint. Reads and close/reopen work must not interleave with an in-flight bulk transaction.

| Operation | Use in this project |
| --- | --- |
| `0x1002` | Standard PTP OpenSession |
| `0x9102` | Panasonic vendor session |
| `0x9108` | Property descriptors / supported settings |
| `0x9402`, `0x9403` | Read / write photo-setting groups |
| `0x9408`, `0x9409` | Read / write movie-setting groups |
| `0x9412`, `0x9706` | Live-view startup and frame acquisition |
| `0x9404`, `0x9405` | Shutter and AF operations |
| `0x9414` | Extended status / exposure groups |
| `0x9416` | Extended camera controls, including native focus assist |

The source is authoritative for payload sizes, response handling and supported values. These codes are implementation facts, not permission to issue arbitrary unbounded commands.

## Live-view parsing and quality

The parser extracts a bounded JPEG from the camera payload. It respects marker structure, checks data boundaries and tolerates unknown metadata. Camera level and other metadata are used only when available. The presentation path favors the newest frame instead of accumulating latency.

The tested GH5 provides a nominal `1280×960 @ 30 fps` mode, while the tested video configuration sends `1280×720` JPEGs. The upstream S5 report of 1024×576 is not a GH5 limit. Conversely, the presence of USB-C does not establish a native 4K tether preview. Query modes, inspect actual JPEG dimensions and avoid inventing unsupported resolutions.

## Native MF Assist

| Control group | Meaning / tested behavior |
| --- | --- |
| `0x03000051` | Assist START / STOP; explicit x/y coordinates are supplied at START |
| `0x03000052` | Assist magnification; a protocol step of 100 produced approximately 1× on the tested GH5 |
| `0x03000053` | Move assist area in the normalized 0–1000 coordinate system |

`FFFF` did not mean “restore the camera's last physical-button position” on this GH5; it started in a corner. Zero coordinates also selected a corner. An immediate MOVE after START could acknowledge without visibly applying. The app sends the saved phone-selected x/y in START and remembers later accepted phone requests.

The exact magnified crop dimensions and the physical joystick's current position are not read back. The navigator must label its position as phone-selected, and must not pretend it is synchronized camera telemetry.

## Property and status groups

Photo groups cover Photo Style, ISO, shutter, aperture, WB, EV, operating mode and preview-related settings. Movie settings occupy namespace `0x06` and use movie read/write operations; sending them with photo write `0x9403` is incorrect for this implementation.

Extended `0x12` groups include recording state, time/capacity and available observed exposure values. Some numeric fields use unknown/unavailable sentinels. Descriptor parsing and model-specific fallbacks are implemented in `PanasonicData.kt`, `RemoteSetting.kt` and `CameraTelemetry.kt` and covered by fixture tests.

## Brightness diagnosis boundary

The app's JPEG/GPU display path is shared between photo and video. That does not prove the camera generates identical previews or that its LCD has the same display transform. The GH5's video Luminance Level setting alone does not establish the encoding range of USB JPEG.

Version 0.5.3 captures original JPEG + decoded PNG + camera/app state for separate photo/video stages. Do not infer signal range from one image's extrema, automatically expand 16–235, or claim a viewing correction changes sensor exposure. Camera LCD brightness, View Assist and video range are not automatically determined by the current diagnostic.
