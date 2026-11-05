# BackdropCam (Android)

Record video with the front or back camera in front of your own backdrop image.
The person is cut out live, composited over the backdrop, recorded as MP4, saved to the
gallery, uploaded to Google Drive and shared as an "anyone with the link" URL.

## Build

1. Open this folder in Android Studio (Ladybug or newer). It creates the Gradle wrapper and
   downloads the Android SDK pieces on first sync.
2. Run on a real phone (Android 8.0+, OpenGL ES 3.0). Emulators do not give a usable camera or encoder.

## Google Drive setup (one time)

The upload uses the Google Identity Authorization API with the narrow `drive.file` scope
(the app can only touch files it creates).

1. Google Cloud Console: create a project and enable **Google Drive API**.
2. OAuth consent screen: add the `.../auth/drive.file` scope and add yourself as a test user.
3. Credentials: create an **OAuth client ID of type Android** with package name `com.backdropcam`
   and the SHA-1 of your signing key (`./gradlew signingReport`).
4. No key file goes into the app. Google matches the package name and SHA-1.

## How it works

| Piece | File |
|---|---|
| Camera at highest resolution (largest 16:9 mode the encoder also supports, up to 4K) | `MainActivity.highestResolutionSelector` |
| Person mask, 256x256, GPU delegate, temporal smoothing | `PersonSegmenter.kt` |
| GL pipeline: camera + mask + backdrop, edge softening, front camera mirroring | `CompositeEngine.kt` |
| H.264 + AAC encode, MP4 into `Movies/BackdropCam` | `VideoRecorder.kt` |
| Drive resumable upload and public link | `DriveUploader.kt` |

The camera frame is composited at full resolution on the GPU. Only a small copy goes to the
segmentation model, and its mask is up-sampled with bilinear filtering.

## Tuning segmentation quality

* Edge softness: `uEdge` in `CompositeEngine` (0.35 to 0.70).
* Stability vs lag: `PersonSegmenter.smoothing`.
* Different model: drop another MediaPipe `.tflite` into `app/src/main/assets` and change
  `PersonSegmenter.MODEL_ASSET`. Check how many masks it returns and which index is the person.

## Not built yet

Video backdrops, landscape recording, tap to focus and zoom, HEVC option, timer.
