# User guide

This guide describes 0.5.3-gh5. Documentation is English; current app labels are Russian, shown below where useful.

For experimental Wi-Fi support on the 0.6.0 branch, see [Wi-Fi setup and controls](WIFI.md). USB instructions below continue to apply.

## Connect

Use a data-capable USB cable and a USB Host / OTG-capable phone. Enable OTG if your phone requires it. Connect the camera, choose **PC(Tether)**, accept Android's USB permission prompt and open GH5 Monitor. **Подключить** means Connect.

If the camera is busy or the stream stops, give recovery a moment. Use **Меню → Связь → Переподключить GH5** (Menu → Connection → Reconnect) if needed. If the USB session remains stuck, disconnect the cable and reconnect in PC(Tether). Avoid switching to another app while monitoring; background continuity is not guaranteed.

## Main screen

The central circular control on the right in landscape, or at the bottom in portrait, is the shutter. The action follows the camera's operating mode: still capture or movie start. When recent camera telemetry confirms recording, the control changes to a red **Стоп** (Stop) button. It is not a phone screenshot button.

ISO, shutter, aperture, white balance and EV open scrolling setting selectors. Values come from the camera's supported choices, subject to its current mode. Unknown or unavailable camera information should not be interpreted as a real exposure value.

### USB white balance (0.7.0 development branch)

Tap **ББ** on the main monitor to open the USB white-balance editor:

- **Режим** (Mode) selects a camera-reported WB preset.
- **Кельвины** (Kelvin) selects K1–K4 and adjusts the selected slot using the camera's reported temperature choices. GH5 specifies 2500–10000 K in 100 K steps. The current K value is also shown in the main WB control when it has been read for that same slot.
- **Подстройка** (Fine adjustment) shows a two-dimensional camera-style grid: A on the left, B on the right, G above and M below. Tap or drag the point, or press **Центр** (Center) to reset both axes. The displayed direction labels correspond to the camera's signed encoding; positive A/B values move toward blue, while positive G/M values move toward green on the tested GH5. These are camera settings, not a phone viewing filter. Auto WB and named presets do not become fixed-K modes; choose a K slot for numeric temperature.
- **Замер** (Measure) selects WB1–WB4 and opens the native camera white-set operation. Fill the center of the frame with a neutral white/gray reference under the shooting light, then press **Измерить и записать** (Measure and store). This replaces the selected custom WB measurement. Entering the page or pressing **Войти в замер ББ** (Enter WB measurement) does not itself take the measurement. **Закрыть замер** closes/cancels the measurement mode.

The app validates the selected WB mode again before queued fine adjustments and before sampling. Sampling requires recording-off telemetry. Changing WB on the body clears the app's old measurement state; each sampling attempt re-enters the native operation and returns to the entry screen afterward. An already-closed native operation is treated as closed, rather than leaving the app stuck after an `InvalidParameter` END response. Closing the editor, leaving the measurement tab, backgrounding the app or gracefully disconnecting attempts to end measurement.

The measurement page estimates central brightness from the uncorrected decoded USB preview, with dark/clipped warnings. This is a JPEG viewing estimate, not a calibrated camera meter, detection of a neutral card or an exact prediction of GH5's white-set limits. The camera's asynchronous white-set failure event is also shown. Its precise reason is not decoded: insufficient light is a possible cause, not an invented camera error code. A command acknowledgement, or absence of a failure event during the short wait, does not prove accurate color calibration. These new controls are USB-only; the Wi-Fi implementation is unchanged.

The main USB mode readout uses the existing dial-position property and, for Creative Video, the reported movie exposure program: **Видео M/A/S/P**. It does not change the physical dial. Camera events trigger coalesced refreshes rather than a full per-frame settings poll.

**PK**, **Z** and **Трети** (Thirds) switch app peaking, zebra and grid independently. They can be enabled together. **AF** triggers autofocus. **MF / Лупа GH5** opens native camera MF Assist.

**Чистый** (Clean) hides the controls. Tap the display or press Android Back to restore them. The app follows screen rotation and provides separate portrait and landscape layouts.

## Native MF Assist

1. Press **MF / Лупа GH5**.
2. On first use, choose an area on the preview or navigator. The app remembers this phone-selected point.
3. Use navigator arrows or drag its map to move the area. Hold arrows for repeated movement.
4. Press or hold **−1× / +1×** to change camera magnification in approximately 1× steps.
5. Press MF again to exit.

This is camera-side magnification, applied before the USB preview. It is separate from the app's **1:1** pixel inspection. The map is a phone-requested position marker, not an exact measured crop box. Physical camera joystick changes and its remembered physical-button area are not synchronized back to the map. Starting video can exit the camera's MF Assist.

## Menu

| Section | Contents |
| --- | --- |
| **Инстр.** / Tools | Full-sized Frame, Focus and Exposure tabs. Their active tools work together. |
| **Камера** / Camera | Focus controls, status, WB, Photo Style, video / preview settings, recent originals. |
| **Экран** / Display | Brightness, viewing correction, LUT import, hold frame, save JPEG, phone MP4 proxy, photo/video comparison. |
| **Связь** / Connection | USB quality, performance / diagnostic log, reconnect and demo mode. |

### Focus and exposure tools

Set app peaking threshold under Focus and zebra threshold under Exposure. False color, histograms, waveform and vectorscope analyze the received JPEG. They do not measure RAW sensor data or reconstruct detail already clipped or compressed by the camera.

Guides, safe area, center marker and camera level are under Frame. Image rotation and anamorphic desqueeze change the preview presentation.

### LUT and viewing correction

Import a `.cube` 3D LUT under Display, then enable it and choose its strength. The parser supports cube sizes 2–64. Match the LUT to the actual incoming picture profile. Output outside 0–1 is clipped; this is an SDR viewing path.

Viewing EV, gamma and contrast change the phone preview only. They do not alter GH5 settings, saved original JPEGs, proxy source frames or exposure-tool input. A display correction is not a substitute for resolving a camera/display mismatch.

### Files and recording

- **Save original JPEG preview:** saves the received USB JPEG without LUT, guides or a new still exposure.
- **Phone MP4 preview:** records a silent proxy from the USB frames. It is distinct from GH5 SD-card REC / STOP.
- **Camera → Originals:** inspects recent camera file handles and lists up to 16 still images among the last 40 handles. Original JPEG transfer was hardware-verified; RW2 needs further validation. Original saving requires Android 10+.

## Compare photo and video brightness

Open **Меню → Экран → Сравнить яркость фото / видео** (Menu → Display → Compare photo/video brightness).

1. Enable **Исходный USB без обработки** (Original USB without app processing). This temporarily bypasses app LUT, viewing correction, false color, peaking and zebra without overwriting their saved settings. Camera-side processing remains present.
2. Use a stationary scene and the same fixed ISO, shutter, aperture and WB in both camera modes. Disable native magnification and held-frame display. Recording is not required.
3. Select photo mode physically on the GH5, wait two seconds, then press **Сохранить этап «Фото»** (Save Photo stage).
4. Select video mode, confirm the same exposure values, wait two seconds, then press **Сохранить этап «Видео»** (Save Video stage).
5. Export the comparison ZIP to **Download/GH5 Diagnostics**.
6. Turn off the temporary neutral preview when finished to restore normal tools.

Each stage includes the next delivered live JPEG, an Android-decoded PNG and JSON with read-only camera parameters plus app settings. These buttons do not trigger the shutter or REC. Camera reads precede frame delivery; this is not a synchronized laboratory exposure measurement.

Brightness statistics use a bilinearly resized 160×120 sample including source borders. Minimum/maximum values do not establish the video signal range or full-resolution clipping. LCD brightness, Luminance Level and camera View Assist are not automatically read or changed. The cause of the reported photo/video brightness difference remains unresolved.

## Demo

Select **Меню → Связь → Демо без камеры** (Menu → Connection → Demo without camera). The app renders a local moving test scene without sending camera commands. Leave demo and reconnect when you want real USB live view.
