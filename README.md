# ML Recorder (prototype)

Android screen recorder prototype targeting Android 10+ (API 29+). It uses MediaProjection, MediaCodec (H.264/AAC), MediaMuxer, and AudioPlaybackCapture for internal playback audio.

## Build on GitHub without a PC

1. Create a new GitHub repository.
2. Upload the **contents** of this folder (the `.github` folder must be included).
3. Commit to the `main` branch, or open **Actions → Build ML Recorder APK → Run workflow**.
4. Open the completed workflow run and download the `ML-Recorder-debug` artifact.

The APK is a debug build and may show an install warning. Only install builds from your own repository.

## First test

Start with 720p, 30 FPS, 6 Mbps. Grant the audio permission and Android screen-capture prompt. While recording, use the persistent notification's **Stop** action. The file is saved under the app's app-specific Movies folder.

## Important limitations

- This is an early prototype, not a validated release. The project has not been built or tested on a Xiaomi Pad 6 in this environment.
- Android only captures playback from apps that allow audio playback capture. Some games/devices may block it.
- Audio and video synchronization, device-specific encoder support, thermal behavior, and sustained 60 FPS need real-device testing. If the muxer fails to start or a file is incomplete, try video-only or 720p/30 FPS.
- Android's screen-capture consent must be granted each session. The app does not bypass Android security restrictions.
- The optional microphone switch currently selects microphone input instead of mixing mic + internal audio. Mixing both is a later feature.
