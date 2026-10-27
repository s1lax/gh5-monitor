# Privacy and diagnostics

The app receives preview images and camera data locally over USB or, on the 0.6.0 branch, Wi-Fi. It contains no built-in analytics, accounts or cloud upload. Android backup is enabled in the manifest, so system-managed backup behavior follows the user's Android settings.

Wi-Fi support requests INTERNET, network/Wi-Fi state, multicast and wake-lock permissions. Camera HTTP and UDP sockets use the selected Android Wi-Fi network or the matching local hotspot interface. Camera addresses are limited to private/local IPv4 ranges; HTTP redirects are disabled. Panasonic's local camera protocol uses unencrypted HTTP. SSDP discovery sends a bounded multicast search on Wi-Fi/hotspot interfaces, without scanning the subnet. Network setup remains in Android settings so the app does not need to read or store network passwords. A random camera-client identifier and the last local camera address are saved in app preferences. The Wi-Fi lock is held only during an active camera session.

JPEG previews, downloaded camera originals, phone MP4 proxies and exported diagnostic ZIP files are saved only when the corresponding action is requested. The app also maintains a local diagnostic log. Exported files can contain your scene, camera settings and file names. Camera originals may carry EXIF and other metadata from the camera.

The experimental debug APK includes optional phone-camera test helpers and requests CAMERA permission. Those tools are not required for USB monitoring. No phone-camera image is uploaded by the application.

Before posting an Issue or diagnostic archive, remove personal images, camera serial numbers, private network addresses, local user paths and any credentials. The original development logs and personal test media are not part of this repository.
