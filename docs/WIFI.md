# Wi-Fi monitor (0.6.2 branch)

Wi-Fi support is experimental. Direct connection, the reported quality modes, ISO write/readback and video REC / STOP have been checked on the development GH5 and OnePlus 8 Pro / Android 13. The published 0.5.3 APK is USB-only. No router, Internet connection, root or HDMI capture device is required for direct camera Wi-Fi.

## Direct connection

1. Disconnect the GH5 USB cable. Close Panasonic Image App and other camera controllers.
2. On GH5 choose **Wi-Fi Function → New Connection → Remote Shooting & View** and direct connection. The camera displays its network / connection instructions.
3. Join that camera network in Android Wi-Fi settings. Accept **stay connected** even though the network has no Internet. Disable automatic switching to another Wi-Fi network if Android leaves it.
4. In GH5 Monitor select **Wi-Fi**. The usual direct camera address is `192.168.54.1`; use the camera's actual local address if different.
5. Press **Подключить Wi-Fi** (Connect Wi-Fi). Approve the camera-client request on GH5 if prompted. Open the app promptly while GH5 is waiting; an unattended connection screen can time out.

You can also join both devices to one router using the camera's **Via Network** option, then enter the camera address or try the app's SSDP search. Multicast discovery can be blocked by guest-network isolation or a router. Shared-LAN discovery is optional; the app does not require the home router.

For development over ADB, a direct camera network usually disconnects ADB over the home Wi-Fi. Connect the **phone to the computer by USB**, leaving the camera USB disconnected, to keep logs and installation access.

## Phone hotspot

The phone can also provide the network. Enable a 5 GHz / WPA2 hotspot in Android, then connect GH5 using **Remote Shooting & View → Via Network**. The camera is the Wi-Fi client in this arrangement. The camera address is assigned by the phone and will usually differ from `192.168.54.1`; enter the address shown in the hotspot's client list or try camera discovery. No Internet / cellular connection is needed for the local camera data itself.

Android does not always expose its hotspot as a normal Wi-Fi `Network`. This transport recognizes a matching local Wi-Fi/hotspot interface, binds UDP and a bounded local HTTP socket to that interface's IPv4 address, and prefers it over an unrelated default network.

**Development result:** a OnePlus 8 Pro hotspot at 5180 MHz successfully carried camera access, HTTP control and 640×360 UDP preview, both with and without a simultaneous home-network connection. It did **not** improve this setup: video arrived in bursts with roughly 0.7-second gaps, and a separate 60-packet ICMP test had about 269 ms average / 1016 ms maximum RTT with no packet loss. JPEG decoding remained around 4 ms. The test was ended and direct camera Wi-Fi was restored. This is a result for this device pair, not a claim that all phone hotspots perform worse. No handset root or Internet connection was used.

## Controls and limits

The existing display tools work on received Wi-Fi frames: app peaking, zebra, guides, scopes, LUT, display correction, 1:1 inspection, preview JPEG saving and silent phone MP4 proxies. These are previews, not original stills or the camera's internal movie recording.

Basic remote operations include autofocus, still capture and video REC / STOP. In the camera menu choose whether the central shutter starts a photo or movie; recent recording telemetry changes it to Stop. This selection changes the button action, not the physical mode dial. ISO, shutter, aperture, exposure compensation, white balance and other options appear only when reported by the camera and can be disabled in its current mode. Settings are sent using Wi-Fi tokens, not USB property codes.

The main status area also displays the reported shooting mode. Photo **M** and Creative Movie **Видео M** (Video M) are distinct: GH5 reports the movie mode and its P/A/S/M exposure program as separate current-menu fields. P/A/S, iA/iA+ and custom modes are decoded when supplied. USB uses its existing mode readout and currently labels its movie mode without a P/A/S/M subtype. This is a read-only display, independent of the central shutter's chosen action. It reuses the periodic menu refresh, so changes can take several seconds to appear.

Native camera MF Assist, navigation, touch-coordinate requests, short manual-focus steps and camera-card browsing are now available over Wi-Fi. The photo/video comparison ZIP tool remains USB-only. Feature availability still depends on camera mode, lens and permissions.

## Native focus and remote priority

On the development GH5, native MF Assist and touch commands were rejected until **Wi-Fi Setup → Priority of Remote Device → Smartphone** was selected and the Wi-Fi session reconnected. This is a camera-side choice: Smartphone priority limits physical camera operation. Camera priority is useful when operating the body, but some app controls may be rejected. The app reports rejection rather than changing priority automatically. See Panasonic's [GH5 Image App guide](https://av.jpn.support.panasonic.com/support/spn/global/dsc/help/image_app/en/camera/DC-GH5/remote.html?no=1).

Smartphone priority is **not required just to monitor**. The [GH5 manual, page 276](https://help.na.panasonic.com/wp-content/uploads/2023/02/DCGH5_DVQP1117ZA_ENG.pdf) describes Camera priority as allowing operation from both the body and smartphone with restrictions on remote changes to dial-controlled settings. It describes Smartphone priority as remote operation with restricted body controls. The priority cannot be changed during an active connection. No verified command was found that keeps all Smartphone-priority operations while unlocking the physical controls; use Camera priority when body operation matters more than those remote commands. The app does not attempt to override that policy.

Select **MF**, then use **Лупа GH5** or double-tap the image. GH5 advertises a full-screen range of 3×–20× and an inset range of 3×–6×. The app requests full-screen assist, uses ±1× steps with press-and-hold, and accepts the camera's actual returned scale and quantized coordinates. Drag the image or use the navigator to move the area; its center button resets the area. The label describes the last camera reply, not continuous physical-joystick tracking. Native magnification operates before the low-resolution Wi-Fi frame is transmitted and does not turn the whole stream into HD or 4K.

**MF ближе / MF дальше** send short focus steps while held. Release cancels queued repetitions; an already accepted step may finish. The GH5 returned `err_param` for the newer `focus-stop` command, which is deliberately not used. **AF касанием кадра** requires a compatible autofocus mode/area; a camera acknowledgement does not prove optical focus was achieved.

## Status and card files

The status strip shows recording, active SD slot, remaining video capacity, camera-reported battery bars, card-full/write warnings and non-normal temperature categories where supplied. The camera menu has details for both slots, remaining photos, grip battery and warnings. Missing data stays unknown. A four-bar battery reading is not an exact percentage, and a temperature category is not degrees Celsius.

Open **Камера → Снимки на карте GH5** while idle. Browsing pauses live view and switches GH5 to playback; exit returns to shooting and restarts the stream. Files are paginated, including an end-of-list shortcut. JPEG viewing loads the original when offered, with a 24 MiB input bound and a decoded preview bounded to 2048 pixels on the long edge. **Сохранить оригинал** saves the camera bytes without re-encoding to `Download/GH5 Originals` on Android 10+. A JPEG already loaded for viewing is reused rather than downloaded again. Other offered originals are streamed with a 1 GiB bound; partial/cancelled files are removed. No deletion or formatting command is provided.

GH5's DIDL list reported every original JPEG as 5,000,000 bytes in testing, including a real 3,490,816-byte file. Transfers therefore validate actual HTTP framing, not that approximate listing field. JPEG saving was independently checked byte-for-byte. RW2, video downloads and a populated second card remain unverified; unavailable camera resources are shown explicitly. Resource URLs are restricted to the connected camera's HTTP file server, without redirects.

## Orientation and frozen frames

Phone rotation rearranges the controls. The visible **↻** button rotates only the preview in 90° increments and saves that angle independently. Guides, AF coordinates and assist navigation use the same transform. A frozen displayed frame is labelled after 1.5 seconds without presentation, even in clean view; an intentional held frame is labelled separately. The age counter measures the last displayed frame, not end-to-end camera latency.

## Quality and delay

Wi-Fi preview resolution is determined by the camera's remote-view protocol and advertised live-view choices. On the tested GH5 video setup, **VGA delivered 640×360** and **QVGA delivered 320×184**, with approximately 30 fps during steady periods. Motion-associated drops to 4–9 fps were also observed; stable 30 fps in every scene is not established. Wi-Fi resolution is substantially below USB tether preview and is independent of internal 4K recording or the phone's panel resolution. The app shows actual decoded JPEG dimensions. It does not invent a 4K option or upscale frames to claim more detail.

UDP reception, JPEG decoding and UI presentation run independently. Pending frame work has capacity one: new frames replace obsolete pending frames instead of building a delay queue. Camera sockets are bound to Wi-Fi even if Android prefers mobile data for Internet. During an active session Android 8–13 uses a high-performance Wi-Fi lock by default. Android 10–13 offers a camera-menu toggle to compare the low-latency lock; Android documents that low-latency mode can trade away throughput. Android 14+ maps the old high-performance mode to low-latency. Wi-Fi locks are requests to the system, not guarantees of radio performance. See [Android's WifiManager documentation](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY).

The performance line reports displayed FPS, complete received-frame FPS, receive bitrate, average datagram size, largest packet-arrival gap, actual JPEG dimensions, decode time and local packet-to-presentation age. **App age excludes camera exposure/readout, camera compression, radio transmission and the final display scanout. It is not end-to-end latency.** Physical performance and perceived lag need testing on each setup. A strong direct link can help, but zero delay is not guaranteed.

The direct GH5 access point uses 2.4 GHz; the camera's 5 GHz support is for joining a network, subject to regional support. If direct-link stability is poor, a separate comparison through a nearby 5 GHz router can help test radio conditions, but is not a promised fix. [Panasonic GH5 specifications](https://panasonic.jp/dc/products/DC-GH5/spec.html)

### Higher-resolution research

The tested GH5 advertised only `vga` and `qvga` for Wi-Fi live view. Bounded requests for `hd`, `fhd`, `1280x720` and `1920x1080` each returned `err_param`; no candidate provided HD. These unsupported probes are not part of normal app operation. Negative results do not prove that every undocumented firmware path has been exhausted.

Other implementations do not establish a higher-resolution GH5 Wi-Fi stream:

- [GMonitor's developer description](https://apps.apple.com/us/app/gmonitor/id1219955645) includes GH4/5 and explicitly describes VGA input scaled on the device.
- [Lumix Remote's live-view notes](https://github.com/sapoepsilon/lumix-remote/blob/main/docs/liveview.md) describe VGA/QVGA input on an S5. Its larger virtual-webcam output is scaling, not evidence of an HD source or GH5 compatibility.
- [LumixCameraAscomDriver](https://github.com/totoantibes/LumixCameraAscomDriver#connection-modes) lists VGA/QVGA UDP live view over Wi-Fi. Downloading a full-resolution still is a different operation from continuous preview.
- [Dragonframe's GH5 page](https://www.dragonframe.com/camera-setup/panasonic_lumix_dc-gh5/) specifies **1280×960 over USB**. That is consistent with this app's existing camera-advertised USB options; a 16:9 video preview can be 1280×720. It is not a Wi-Fi workaround.

Native MF Assist can enlarge the sensor region before Wi-Fi transmission, making it useful for focusing despite the stream size. Software upscaling cannot restore missing source detail. HDMI capture is a separate hardware route to a higher-resolution feed. No verified native HD/4K Wi-Fi or 4K USB tether-preview path for the original GH5 was found in this research; GH5 II streaming features must not be assumed to apply to GH5.

## Recovery

Keep GH5 Monitor in the foreground. On playback, return to shooting on the camera; recovery does not repeatedly force a mode change. A new stream gets at least five seconds to settle before watchdog recovery. A stalled stream gets up to three spaced restart requests only while the camera confirms shooting, not recording, and not busy with card access / interval shooting. Receiving frames resets that recovery budget.

Control timeouts keep the UDP receiver open while video is still arriving. Full reconnect requires both a prolonged control outage (20 seconds) and stale video (15 seconds); it then uses up to four attempts with increasing delays. A busy START during reconnect keeps the receiver rather than discarding already arriving frames. An explicit camera access rejection ends the session. Disconnecting the monitor does not intentionally stop an ongoing camera recording.

If the camera reports connection failure, reopen Remote Shooting & View and reconnect the phone. If Image App or another controller owns the session, close it first. The app does not bypass camera pairing or access approval.

## Protocol references

The local HTTP + UDP implementation was researched using [pylumix](https://github.com/abichinger/pylumix), [Lumix Remote](https://github.com/sapoepsilon/lumix-remote), the [libgphoto2 Lumix driver](https://github.com/gphoto/libgphoto2/blob/master/camlibs/lumix/lumix.c) and the [official GH5 manual](https://help.na.panasonic.com/wp-content/uploads/2023/02/DCGH5_DVQP1117ZA_ENG.pdf). Lumix Remote documents S5 behavior; its hardware results do not establish GH5 support. This app does not redistribute Panasonic SDK binaries or manuals.
