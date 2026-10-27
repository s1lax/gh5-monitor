# Validation record

## 0.6.2-gh5 shooting-mode readout and resolution research

**124 JVM tests pass**, with no failures, errors or skipped tests. New cases distinguish photo M from Creative Movie M, parse its separate exposure-program field, clear stale submodes on mode changes, ignore inactive mode entries and handle custom/automatic/unknown modes.

On the development GH5 / OnePlus 8 Pro / Android 13, the installed build displayed **M**, then **Видео M** after one advertised remote mode change, then **M** after restoring the original photo mode. Both labels were verified in the actual phone UI. All four UI orientations retained one Wi-Fi session, a centered shutter and the manually selected preview angle; the user's original angle was restored. Final checks confirmed fresh 640×360 frames at approximately 30 FPS, recording off, native assist off and no fatal entries for the test process. This is a short hardware check, not a long-run stability result.

The camera advertised only VGA/QVGA Wi-Fi sizes. Four bounded `liveviewsize` candidates (`hd`, `fhd`, `1280x720`, `1920x1080`) returned `err_param`. A subsequent direct size query returned `err_critical`, and the next connection delivered QVGA despite a VGA menu value. Selecting the advertised VGA option through the app restored actual 640×360 JPEGs. Unsupported probes are excluded from production operation; rejected commands must not be assumed to have no side effects on this firmware. References and the distinction between native input, upscaling and USB preview are recorded in [WIFI.md](WIFI.md#higher-resolution-research).

The prior Camera-priority MF Assist rejection and Smartphone-priority success remain the hardware evidence for that restriction. The manual permits Camera priority for simultaneous body/phone operation with limited remote controls; no full-control bypass was established. USB mode display reuses existing property readout; a fresh USB hardware check was not performed for this badge-only change.

## 0.6.1-gh5 Wi-Fi focus and files

**119 JVM tests pass**, with no failures, errors or skipped tests. Added tests cover native assist CSV replies and advertised limits, battery precision, missing/dual-card fields, SOAP/DIDL parsing, unsafe resource URLs, XML entities and bounded binary HTTP transfers.

Checked on the development GH5 and OnePlus 8 Pro / Android 13:

- Native Wi-Fi MF Assist required MF and Smartphone remote-device priority. Start, full-screen mode, ±1× scale changes, held scale controls, center reset, movement and exit were acknowledged by GH5. Its replies included actual scale and quantized area coordinates.
- A 950 ms held scale control changed 12× to 16× through repeated 1× steps. Releasing the short lens-focus control invalidates queued steps. The newer `focus-stop` command returned `err_param` and was removed from the GH5 path.
- Center touch in AFS was acknowledged with press/release, the target marker appeared, and remaining still capacity did not change. MF was restored. This checks command handling, not optical focus accuracy for every AF mode.
- Card listing returned 64 entries with pagination. A 3712×2480 original JPEG opened and its saved 3,490,816 bytes matched an independent camera HTTP read exactly. Its listed DIDL size was only an estimate. Exiting playback resumed fresh live frames.
- All four UI orientations retained a manually selected 180° preview angle, a centered shutter, visible rotation control and the same Wi-Fi session. The prior user angle was restored afterward.
- REC and SD1 write activity were confirmed, followed by recording-off. An immediate STOP while START was pending also completed successfully after correcting the command queue.
- Suspending presentation while leaving reception alive displayed the explicit frozen-frame warning; restoring presentation cleared it. This is a UI-age test, not proof that radio interruptions have been eliminated.

The source is still camera-generated VGA Wi-Fi preview. Long-run radio reliability, RW2/movie transfers, a populated SD2, other bodies/lenses and physical camera-joystick synchronization remain unverified. No personal frames or raw device logs are published.

## 0.6.0-gh5 Wi-Fi branch

That development build passed **111 JVM tests**, including 29 Wi-Fi parsing, setting-token, bounded local HTTP and recovery tests. The debug APK was installed on the development OnePlus 8 Pro / Android 13.

Hardware checks so far:

- Direct GH5 Remote Shooting & View access, camera approval, local HTTP control and UDP preview.
- Actual VGA 640×360 and QVGA 320×184 frames; approximately 30 fps during steady periods. VGA restored after the quality comparison.
- ISO 100 → 125 write/readback and restoration to 100.
- Video recording was confirmed by camera telemetry; STOP was sent and recording-off was confirmed.
- Four screen orientations with a centered shutter and the same Wi-Fi session.
- A phone-provided 5 GHz hotspot successfully carried camera HTTP and UDP preview, but delivered bursty video and high independent ICMP RTT on the tested pair. Disabling the phone's simultaneous STA connection did not resolve it. Direct camera Wi-Fi was restored after the experiment.
- App rendering and JPEG decoding remained fast during a reported receive-rate collapse. Logs showed control timeouts alongside UDP delivery gaps; the early implementation then closed a still-live video receiver. Recovery now treats the paths separately, with regression tests for this condition and the premature START watchdog issue.

**Unresolved / pending:** motion-associated radio or camera stalls, long-run reliability after the latest recovery fix, exact end-to-end latency, shared-router operation, AF / still capture and every setting on the Wi-Fi path. The recovery fix retained a single session through control outages and returned to approximately 30 fps, but prolonged gaps in received video remained. The user described steady-period lag as almost unnoticeable, which is subjective feedback rather than a latency measurement. No personal scene images, device identifiers or raw network logs are published.

## 0.5.3-gh5

The development build passed **82 JVM unit tests** with no failures, errors or skipped tests. A debug APK was built and installed; its installed SHA-256 matched the distributed file:

```text
e8ad7e44e63ace9913486076ef999df4347b4be7558689ebba2be311e81e742c
```

On the tested GH5 / OnePlus setup, the diagnostic capture successfully read 11 camera parameter groups and saved a 1280×720 video preview in Natural Photo Style. Android's decoded PNG matched an independent desktop JPEG decoder pixel-for-pixel for this captured frame. The exported ZIP contained byte-identical original JPEG, PNG and JSON files.

The diagnostic panel was opened in portrait and landscape while keeping the same USB session. Toggling neutral viewing preserved saved tool / display settings, and normal viewing was restored. The final observed preview was around 30 fps, with no crash entries for that test process.

**Pending:** a controlled identical-scene photo-versus-video capture and physical GH5 LCD comparison. The brightness issue is not marked fixed. One matching JPEG decoder result does not establish correctness of every display path or mode.

## Earlier hardware checks retained in 0.5.3

- **0.5.2:** four screen orientations, centered shutter placement, main touch targets of at least 48 dp without overlap, reachable moved actions, clean-view exit and enlarged native assist navigation; same USB session across the rotation checks.
- **0.5.1:** native MF Assist starts with explicit phone-selected coordinates; visually checked center and off-center positions on the GH5; phone-selected position persistence; ±1× step confirmed; REC / STOP and selected movie quality readback.
- **0.5.0:** original camera JPEG transfer verified byte-for-byte against an independent card read; recording telemetry and elapsed timer checked; selected WB and other property writes read back and restored.
- **0.3.2:** periodic preview stalls disappeared on the Android 13 test setup after shortening the interrupt-event read timeout; the user also confirmed the improvement at the higher selected preview mode.

These were development-session checks, not automated hardware CI. They do not establish every lens, firmware, phone or exposed camera value. Raw logs, personal image frames, network addresses and device identifiers are intentionally not published.
