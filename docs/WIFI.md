# Wi-Fi monitor (0.6.0 branch)

Wi-Fi support is experimental. Direct connection, the reported quality modes, ISO write/readback and video REC / STOP have been checked on the development GH5 and OnePlus 8 Pro / Android 13. The published 0.5.3 APK is USB-only. No router, Internet connection, root or HDMI capture device is required for direct camera Wi-Fi.

## Direct connection

1. Disconnect the GH5 USB cable. Close Panasonic Image App and other camera controllers.
2. On GH5 choose **Wi-Fi Function → New Connection → Remote Shooting & View** and direct connection. The camera displays its network / connection instructions.
3. Join that camera network in Android Wi-Fi settings. Accept **stay connected** even though the network has no Internet. Disable automatic switching to another Wi-Fi network if Android leaves it.
4. In GH5 Monitor select **Wi-Fi**. The usual direct camera address is `192.168.54.1`; use the camera's actual local address if different.
5. Press **Подключить Wi-Fi** (Connect Wi-Fi). Approve the camera-client request on GH5 if prompted. Open the app promptly while GH5 is waiting; an unattended connection screen can time out.

You can also join both devices to one router using the camera's **Via Network** option, then enter the camera address or try the app's SSDP search. Multicast discovery can be blocked by guest-network isolation or a router. Shared-LAN discovery is optional; the app does not require the home router.

For development over ADB, a direct camera network usually disconnects ADB over the home Wi-Fi. Connect the **phone to the computer by USB**, leaving the camera USB disconnected, to keep logs and installation access.

## Controls and limits

The existing display tools work on received Wi-Fi frames: app peaking, zebra, guides, scopes, LUT, display correction, 1:1 inspection, preview JPEG saving and silent phone MP4 proxies. These are previews, not original stills or the camera's internal movie recording.

Basic remote operations include autofocus, still capture and video REC / STOP. In the camera menu choose whether the central shutter starts a photo or movie; recent recording telemetry changes it to Stop. This selection changes the button action, not the physical mode dial. ISO, shutter, aperture, exposure compensation, white balance and other options appear only when reported by the camera and can be disabled in its current mode. Settings are sent using Wi-Fi tokens, not USB property codes.

Native camera MF Assist, its navigator, tap AF coordinates, motorized focus controls, original JPEG/RW2 transfers and the photo/video comparison capture are currently USB-only. No claim of USB feature parity is made.

## Quality and delay

Wi-Fi preview resolution is determined by the camera's remote-view protocol and advertised live-view choices. On the tested GH5 video setup, **VGA delivered 640×360** and **QVGA delivered 320×184**, with approximately 30 fps during steady periods. Motion-associated drops to 4–9 fps were also observed; stable 30 fps in every scene is not established. Wi-Fi resolution is substantially below USB tether preview and is independent of internal 4K recording or the phone's panel resolution. The app shows actual decoded JPEG dimensions. It does not invent a 4K option or upscale frames to claim more detail.

UDP reception, JPEG decoding and UI presentation run independently. Pending frame work has capacity one: new frames replace obsolete pending frames instead of building a delay queue. Camera sockets are bound to Wi-Fi even if Android prefers mobile data for Internet. During an active session Android 8–13 uses a high-performance Wi-Fi lock by default. Android 10–13 offers a camera-menu toggle to compare the low-latency lock; Android documents that low-latency mode can trade away throughput. Android 14+ maps the old high-performance mode to low-latency. Wi-Fi locks are requests to the system, not guarantees of radio performance. See [Android's WifiManager documentation](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY).

The performance line reports displayed FPS, complete received-frame FPS, receive bitrate, average datagram size, largest packet-arrival gap, actual JPEG dimensions, decode time and local packet-to-presentation age. **App age excludes camera exposure/readout, camera compression, radio transmission and the final display scanout. It is not end-to-end latency.** Physical performance and perceived lag need testing on each setup. A strong direct link can help, but zero delay is not guaranteed.

The direct GH5 access point uses 2.4 GHz; the camera's 5 GHz support is for joining a network, subject to regional support. If direct-link stability is poor, a separate comparison through a nearby 5 GHz router can help test radio conditions, but is not a promised fix. [Panasonic GH5 specifications](https://panasonic.jp/dc/products/DC-GH5/spec.html)

## Recovery

Keep GH5 Monitor in the foreground. On playback, return to shooting on the camera; recovery does not repeatedly force a mode change. A new stream gets at least five seconds to settle before watchdog recovery. A stalled stream gets up to three spaced restart requests only while the camera confirms shooting, not recording, and not busy with card access / interval shooting. Receiving frames resets that recovery budget.

Control timeouts keep the UDP receiver open while video is still arriving. Full reconnect requires both a prolonged control outage (20 seconds) and stale video (15 seconds); it then uses up to four attempts with increasing delays. A busy START during reconnect keeps the receiver rather than discarding already arriving frames. An explicit camera access rejection ends the session. Disconnecting the monitor does not intentionally stop an ongoing camera recording.

If the camera reports connection failure, reopen Remote Shooting & View and reconnect the phone. If Image App or another controller owns the session, close it first. The app does not bypass camera pairing or access approval.

## Protocol references

The local HTTP + UDP implementation was researched using [pylumix](https://github.com/abichinger/pylumix), [Lumix Remote](https://github.com/sapoepsilon/lumix-remote), the [libgphoto2 Lumix driver](https://github.com/gphoto/libgphoto2/blob/master/camlibs/lumix/lumix.c) and the [official GH5 manual](https://help.na.panasonic.com/wp-content/uploads/2023/02/DCGH5_DVQP1117ZA_ENG.pdf). Lumix Remote documents S5 behavior; its hardware results do not establish GH5 support. This app does not redistribute Panasonic SDK binaries or manuals.
