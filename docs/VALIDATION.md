# Validation record

## 0.7.1-gh5 White Set layout

**135 JVM tests pass** with no failures, errors or skipped tests. New tests check WB-family separation and centered, bounded reference sampling at tiny, HD and 4K image dimensions.

On GH5 / OnePlus 8 Pro / Android 13, the new UI was checked for preset-only entries, four K slots, four WB slots, a mode-labelled fine-adjustment screen and native measurement entry without sampling. Portrait and landscape screenshots confirmed the yellow guide and reachable SET / Back controls. Fresh USB JPEGs continued during measurement, Android Back returned to WB selection and the same USB camera instance survived the orientation change. The pre-test WB1 selection and orientation request were restored. This check did not overwrite a custom measurement.

After final text-width refinements, the installed build was checked again for readable AWB/AWBc/AWBw, WB1-labelled fine adjustment, White Set entry and on-screen Back with a closed-state confirmation. Grid tap/drag readback and Center passed in the scrollable editor; original axis values were restored. Final smoke: fresh 1280×720 USB JPEGs at approximately 30 FPS, Video M, WB1, recording off, measurement closed, the user's 90° preview angle and no fatal entries for that process. The distributed debug APK's SHA-256 matches the installed APK.

The visual center guide approximates the native White Set screen. GH5 does not provide its exact sensor ROI through the implemented USB commands. Brightness describes decoded JPEG samples from the displayed guide; it is not a calibrated sensor measurement. Successful neutral-reference calibration and long-run reliability remain unverified.

## 0.7.0-gh5 USB white balance

**133 JVM tests pass**, with no failures, errors or skipped tests. Added cases cover native white-set packet lengths and UINT32 coordinates, rejection of unrelated control IDs and vendor events, an already-closed END reply, matched-slot Kelvin display, movie exposure labels, grid coordinate round trips and preview brightness warnings.

Checked with the development GH5 and OnePlus 8 Pro / Android 13 over USB PC(Tether), using phone Wi-Fi debugging:

- The main monitor reports Creative Video **Видео M** from the dial-position property and separate movie exposure program.
- K1–K4 read as independent slots. Main-editor temperature writes/readback were checked at K1 5300 → 5400 K and K2 4000 → 4100 K, then restored. K3 3200 K and K4 5600 K were read. These were the values at that test, not factory defaults.
- A/B +1 and G/M −1 writes were acknowledged and read back, then restored. Additional bounded ±3 checks confirmed that positive A/B adds blue and positive G/M adds green. The grid labels use that hardware-checked polarity.
- Grid taps and drags produced the requested B3/G3 and A3/G3 values; Center reset both axes. Portrait/landscape controls remained reachable and the USB camera instance survived the orientation change. Current per-mode shifts were restored after the test.
- Native measurement START/END were acknowledged without changing the selected custom WB properties. After changing WB during an open measurement, the new implementation returned to entry and allowed repeated entry/exit. The test uses a camera WB write, not a physical dial gesture; the user separately reported the original problem after body WB changes.
- The first implementation accepted sampling but later received camera event `0xC212`, matching the user's report that GH5 rejected the dark reference. After manual WB changes, stale SAMPLE/END calls returned `InvalidParameter`. The final implementation handles the failure event and already-closed END separately, re-enters before sampling and clears stale state on a fresh WB-mode change.
- Two subsequent samples each received command acceptance followed by `0xC212` with white-set failure ID `0x12800042`. Both returned to the entry screen with a failure message and allowed another attempt. Unrelated vendor-event parameters are ignored. This checks failure recovery, not successful neutral-reference calibration.
- The final installed 0.7.0 build passed a repeated grid tap/drag/readback and landscape-fit check with the same USB camera instance. A closing smoke check found fresh 1280×720 JPEGs at approximately 30 FPS, Video M, WB4 selected, measurement closed, recording off and no fatal entries for that process. The user's 90° preview angle and manual exposure changes were retained. These are short session checks, not long-run reliability or end-to-end latency measurements.

The user also changed camera exposure/WB while testing. Those manual exposure changes were preserved. WB1 was designated as the disposable measurement-test cell with the user's authorization; the selected mode was returned to WB4. A valid neutral-reference measurement and colorimetric accuracy are not established by a command acknowledgement. The exact reason field for the camera failure is not decoded. Central brightness is a bounded, rate-limited analysis of decoded USB JPEG pixels before phone viewing correction; it is not a calibrated sensor meter. Wi-Fi behavior and the rendering/peaking/zebra pipeline were not changed.

## 0.6.3-gh5 mode refresh cadence

**124 JVM tests pass**, with no failures, errors or skipped tests, and the debug APK build succeeded. ADB reported successful installation on the development phone. Foreground current-menu polling now runs every supervisor cycle before lens reads, replacing the previous every-fifth-cycle refresh. Polling remains serialized and uses fixed delay, so slow camera requests do not create catch-up polls.

The user confirmed that only the mode label lagged after a physical dial change; the image changed normally with Camera remote-device priority. ADB disconnected during the post-installation measurement, so the new physical-dial-to-label delay and steady-stream performance have not yet been confirmed on hardware. The one-second supervisor delay is not a guaranteed one-second end-to-end update time. No camera mode writes were added.

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
