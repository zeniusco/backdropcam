# BackdropCam (Android)

Record video with the front or back camera in front of your own backdrop. You are cut out live,
placed over the backdrop, and the result is saved as an MP4 in the gallery (Movies/BackdropCam).
Optionally it uploads to Google Drive and copies a share link.

## Get the app

Every push builds an APK. Download `BackdropCam.apk` from the latest
[release](../../releases) on your phone and install it.

## Features

- Front and back camera, highest resolution the camera and encoder support (up to 4K)
- Ultra-wide 0.6x lens when the phone exposes it
- Picture shape: FULL (fills the screen), 16:9, or 4:3 (widest view)
- Backdrops: your images, Blur, or None; resize and move yourself, match brightness and warmth
- Portrait or landscape recording, follows how you hold the phone
- Pause / resume, countdown timer, auto-stop (30 to 180 s)
- Mic on/off, volume 0 to 200 %, live level meter, mute while recording
- Hands-free: spoken framing guidance, say "start" / "stop" (offline, no internet)
- Teleprompter for the front camera: type, paste, share text in, or import .txt / .docx
- Volume buttons and Bluetooth selfie remotes start and stop recording
- Google Drive upload with an "anyone with the link" URL
- Formats: 9:16 (Reels/Shorts, or 16:9 sideways for YouTube), FULL, 1:1 square, 4:3
- Logo overlay: drag, pinch, twist, opacity, in front of or behind you, per backdrop
- Brand kit: name title (lower third), brand colour, intro card; share the kit as a .zip
- Trim (no re-encoding) and one-tap delete and retake
- Video backdrops (looping, silent)
- Background music mixed under your voice, quieter while you speak
- Live captions in the video and an .srt subtitle file (English, offline)
- Teleprompter that follows your voice
- Second-screen remote: watch and control from any browser on the same Wi-Fi

## Google Drive setup (one time)

The upload uses the Google Identity Authorization API with the narrow `drive.file` scope
(the app can only touch files it creates).

1. Google Cloud Console: create a project and enable **Google Drive API**.
2. OAuth consent screen: add the `.../auth/drive.file` scope and add yourself as a test user.
3. Credentials: create an **OAuth client ID of type Android** with package name `com.backdropcam`
   and the SHA-1 of the signing key. The CI build uses `app/debug.keystore`:
   `3C:7C:76:CC:B0:BC:0D:A2:02:8B:33:C5:DA:D0:5B:C6:A7:88:79:13`

## How it works

| Part | File |
|---|---|
| Camera screen and UI wiring | `MainActivity.kt` |
| Lens discovery, resolution choice | `CameraLenses.kt` |
| GPU pipeline: camera + cut-out + backdrop, orientation, edge refinement | `CompositeEngine.kt` |
| Person segmentation (MediaPipe selfie multiclass, or the fast selfie model) | `PersonSegmenter.kt` |
| H.264 + AAC encoding, pause, saving to the gallery | `VideoRecorder.kt` |
| Hands-free: speech, framing coach, offline keyword spotting (Vosk) | `HandsFree.kt` |
| Mic level test | `MicMonitor.kt` |
| Backdrops and teleprompter scripts | `BackdropStore.kt`, `ScriptStore.kt` |
| Google Drive upload | `DriveUploader.kt` |
| Preferences | `AppSettings.kt` |

The segmentation model runs on a small copy of the exact frame being shown, so the mask always
lines up with the picture. The camera itself is composited at full resolution, and the mask edges
are refined on the GPU against the full-resolution colours.
