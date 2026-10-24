# Contributing

Bug reports, camera compatibility reports, documentation fixes and pull requests are welcome.

## Report a bug

Include app version, camera model / firmware, phone / Android version, lens and cable / OTG setup. Describe reproduction steps and distinguish USB preview, camera SD recording and phone proxy recording. State the actual received JPEG dimensions if relevant.

For brightness issues, use a stationary scene and identical manual exposure / WB settings across photo and video; see [the user guide](docs/USAGE.md). A photograph of two different screens is useful context but not a calibrated measurement.

Review logs and images before uploading. Remove serial numbers, private addresses, personal scenes, credentials and downloaded vendor documents. Do not post a full SDK archive.

## Submit changes

1. Create a branch from `main`.
2. Keep camera operations serialized and bounded. Preserve explicit unknown states.
3. Add meaningful tests for parser, protocol or numerical changes.
4. Run `./gradlew testDebugUnitTest assembleDebug` using JDK 17 and SDK 34.
5. Explain the problem, changed behavior, checks performed and any remaining hardware gap in the pull request.

Do not claim a camera feature is validated just because a command returned OK. For native MF area changes, verify the actual camera image and distinguish phone-requested position from physical readback. Avoid backgrounding the monitor during USB tests.

Keep public documentation in English. The current UI still uses Russian labels; a proper resource-based localization contribution is welcome. Preserve existing MIT copyright notices and add attribution for new third-party material. AI-assisted contributions are welcome when their behavior and testing are explained honestly.
