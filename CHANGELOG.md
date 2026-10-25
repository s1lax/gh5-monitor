# Changelog

This is the first public GH5-focused release. Earlier version entries summarize the local development iterations; separate public release binaries are not provided for every historical version.

## 0.6.0-gh5 — Wi-Fi connection (in development)

- Add direct-camera / shared-LAN Wi-Fi connection, manual local address and bounded SSDP discovery.
- Separate HTTP camera control from UDP reception, decoding and presentation, with capacity-one frame hand-offs and stale-frame rejection.
- Reuse monitor tools, setting wheels, preview JPEG saving and phone proxy recording with Wi-Fi frames.
- Read advertised setting values and validate camera command results rather than treating HTTP 200 as success.
- Add basic AF, still capture and video REC / STOP, heartbeat and bounded reconnect attempts.
- Keep native MF Assist, touch AF, original-file transfer and comparison diagnosis USB-only for now.
- Preserve an arriving UDP stream across control timeouts, tolerate a busy stream start on reconnect, and prevent a watchdog START immediately after a successful START.
- Add received FPS, bitrate, packet-gap diagnostics and a Wi-Fi performance profile comparison on Android 10–13.
- 106 JVM tests pass, including 24 Wi-Fi protocol / recovery tests. Direct GH5 live view, quality changes, ISO readback, REC / STOP and four orientations were checked. Motion-associated receive stalls remain under investigation; no guaranteed-FPS or zero-latency claim.

## 0.5.3-gh5 — Photo/video brightness diagnosis

- Add a comparison panel with original USB JPEG, decoded PNG and read-only camera/app settings per photo/video stage.
- Add temporary neutral viewing without overwriting saved LUT / correction / tool settings.
- Export a comparison ZIP and restore the latest saved stages between launches.
- Preserve the existing rendering pipeline; no automatic range, gamma or exposure correction.
- 82 unit tests passed. Video capture, independent JPEG decode equality, exact ZIP contents and both panel orientations checked on hardware. Controlled photo/video and physical LCD comparison remain pending.

## 0.5.2-gh5 — Larger controls

- Center and enlarge the shutter; remove its visible still-shutter caption.
- Simplify main quick controls and move quality, camera panel and JPEG actions into the menu.
- Enlarge focus navigation and menu targets; verify four orientations without replacing the USB session.

## 0.5.1-gh5 — Native assist area and portrait layout

- Send explicit saved phone-selected coordinates in native MF Assist START; fix the corner-start assumption.
- Correct zoom steps to approximately 1× and add hold-to-repeat controls.
- Add a navigator and explain that physical camera joystick state is not read back.
- Use movie write operation 0x9409 for movie properties; improve confirmed REC / STOP UI behavior.
- Add optional in-Activity debug phone-camera capture to avoid opening the stock camera app during visual checks.

## 0.5.0-gh5 — Extended camera control

- Add camera status, recording telemetry, extended WB / Photo Style / movie settings and camera-provided value selectors.
- Add touch AF requests, motor focus controls and native assist movement.
- Add recent camera still browsing and original JPEG / RW2 transfer paths. JPEG transfer checked byte-for-byte; RW2 remains partly unverified.

## 0.4.6-gh5 — Camera-native MF Assist

- Add real GH5 MF Assist control before USB transmission, distinct from phone-side digital magnification.

## 0.4.5 / 0.4.4-gh5 — Preview quality and parsing

- Probe supported USB preview modes and inspect actual received JPEG dimensions.
- Tighten live-view payload boundaries and JPEG parsing.
- Keep 4K USB preview explicitly unsupported rather than guessing undocumented modes.

## 0.4.3 / 0.4.2-gh5 — Settings and orientation

- Replace long setting lists with discrete scrolling selectors.
- Add shutter, focus inspection and orientation handling.

## 0.4.1 / 0.4.0-gh5 — Field monitor tools

- Add GPU peaking, zebra, false color, scopes, LUT viewing, framing tools and proxy recording.
- Allow tools from different pages to work simultaneously; improve clean-view exit and viewing controls.

## 0.3.2-gh5 — Periodic Android 13 stalls

- Shorten interrupt-event read timeout to remove the regular preview stalls observed on the test phone.
- Preserve event handling and latest-frame presentation. The proposed JNI / GC explanation remains an inference, not a complete execution trace.

## 0.3.0 / 0.2.0-gh5 — Initial adaptation

- Adapt the upstream Android USB monitor to the GH5 and improve live-view recovery, device parsing, framing controls and diagnostics.
