# Privacy and diagnostics

The app receives preview images and camera data locally over USB. The main Android manifest does not request Internet permission, and the project contains no built-in analytics, accounts or cloud upload. Android backup is enabled in the manifest, so system-managed backup behavior follows the user's Android settings.

JPEG previews, downloaded camera originals, phone MP4 proxies and exported diagnostic ZIP files are saved only when the corresponding action is requested. The app also maintains a local diagnostic log. Exported files can contain your scene, camera settings and file names. Camera originals may carry EXIF and other metadata from the camera.

The experimental debug APK includes optional phone-camera test helpers and requests CAMERA permission. Those tools are not required for USB monitoring. No phone-camera image is uploaded by the application.

Before posting an Issue or diagnostic archive, remove personal images, camera serial numbers, private network addresses, local user paths and any credentials. The original development logs and personal test media are not part of this repository.
