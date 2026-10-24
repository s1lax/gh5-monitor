# Attribution and third-party notices

## Original application

This repository is a GH5-focused fork of [Lumix Monitor by velkilllmer](https://github.com/velkilllmer/lumix-USB-tether-monitor-android). Its source, history and MIT notices are retained. The upstream license begins with `Copyright (c) 2026 Lumix Monitor contributors`.

GH5 adaptation, project direction and hardware testing: **s1lax**. Development, investigation, tests and documentation were created with **GPT / OpenAI Codex** under s1lax's direction. OpenAI and Panasonic do not endorse this project.

## Tethr

The upstream Panasonic PTP work credits [Tethr by Baku Hashimoto](https://github.com/baku89/tethr), MIT, copyright 2021 Baku Hashimoto. The required MIT notice remains in [LICENSE](LICENSE).

## Gradle wrapper

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are Gradle wrapper components under Apache License 2.0. The license is included at [gradle/wrapper/LICENSE](gradle/wrapper/LICENSE). Original copyright headers are preserved.

## Android dependencies

AndroidX / Jetpack Compose and Kotlin components are declared in the Gradle build files and obtained from their normal repositories. Their upstream licenses continue to apply; this project's MIT license does not relicense dependencies.

## Panasonic materials

The separately downloaded LUMIX Remote Control Library and its original documentation were consulted during development. No downloaded Panasonic DLL, SDK header, sample executable, manual or SDK source is included here. The app uses its own Kotlin USB PTP implementation and does not link Panasonic libraries.

## Other protocol research references

The upstream project also referenced [libgphoto2](https://github.com/gphoto/libgphoto2), [pylumix](https://github.com/abichinger/pylumix) and [LumixController](https://github.com/davidkim9/LumixController). These references do not mean their code is bundled or linked into this application.
