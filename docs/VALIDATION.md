# Validation record

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
