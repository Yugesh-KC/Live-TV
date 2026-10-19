# Live TV (Android)

A Kotlin phone app that plays live TV channels from an M3U playlist with Media3/ExoPlayer.
Connect the phone to a TV with a USB-C to HDMI cable and the video moves to the TV while
the phone becomes the remote.

## Features

- Loads `https://iptv-org.github.io/iptv/index.m3u` by default. You can set any M3U URL in Settings.
- Parses the name, `tvg-logo`, `group-title` (split on `;`), country and stream URL.
  - Country comes from `tvg-country`, or from the iptv-org `tvg-id` suffix (`Foo.us@SD` → US).
  - `#EXTVLCOPT` user-agent and referrer headers are passed to the stream.
- Channel list with logos, category and country filters, and search by name.
- Favorites (star icon) are saved locally and listed in a section at the top.
- Plays HLS (`.m3u8`), DASH and progressive streams with ExoPlayer.
- Portrait: a 16:9 player on top with the list below. Landscape or the fullscreen button: fullscreen video.
- Next/previous channel: swipe left or right on the video, or use the ⏮ ⏭ buttons.
- If a stream fails or doesn't start within 20 s, the app shows "Channel unavailable" instead of crashing.
- Audio codecs that phones usually can't decode (MP2, AC-3, E-AC-3, DTS) are played with the FFmpeg decoder
  (`org.jellyfin.media3:media3-ffmpeg-decoder`). If a channel's audio still can't be decoded, a message says so.
- A channel that was playing reconnects by itself after a network glitch (up to 5 times a minute).
- URLs without a `.m3u8`/`.mpd` extension that fail as plain video are retried once as HLS.
- **Backup links:** quality feeds of one channel (`Foo.us@SD`, `Foo.us@HD`) are merged into one entry, and
  backup playlists (default: [Free-TV](https://github.com/Free-TV/IPTV), editable in Settings) add more links
  to channels with the same `tvg-id`. If a link fails, the next one is tried; the link that worked is
  remembered for the session.
- **Quality:** the HD button (under the video, on the TV remote, or top-right in fullscreen) picks Auto,
  Data saver (lowest) or a maximum resolution. It only changes anything on channels that offer several
  qualities (about 1 in 5).
- **YouTube tab:** the mobile YouTube site in a WebView. YouTube's fullscreen button switches to landscape.
  With HDMI connected the TV mirrors the phone on this tab. Google sign-in may be blocked inside WebViews.
- Debug builds accept test commands over adb, for phones that block simulated taps:
  `adb shell "am start -n com.livetv.app/.MainActivity --es test_play 'Channel name' --ei test_quality 360"`
  (also `test_tab youtube|live`, `test_url <url>`, `test_quality_picker true`, `test_orientation portrait`).
- The playlist is cached on disk, so the app opens instantly and refreshes in the background.
  Pull down on the list to refresh it by hand.
- **TV mode (HDMI):**
  - `DisplayManager` listens for displays being added or removed.
  - A `Presentation` plays fullscreen video on the TV.
  - The phone shows a remote: channel list, search, favorites, ⏮ ⏯ ⏭ and volume.
  - The channel name appears on the TV for 3 s after each switch.
  - Unplugging the cable moves the video back to the phone with `PlayerView.switchTargetView`, without stopping the stream.
  - If the display can't host a Presentation (plain mirroring), the app switches to fullscreen landscape.
  - The screen stays awake during playback. The phone screen can dim while the TV plays; touch it to brighten for 8 s.
- **Chromecast / Google TV:** tap the Cast button in the toolbar (it shows up when a Cast device is on the
  same Wi-Fi). The TV fetches and plays the stream itself, and the phone becomes the remote: ⏮ ⏯ ⏭ and the
  TV's volume (also with the volume keys). Uses Google's Default Media Receiver, so it needs Google Play services.
  - Fewer channels play than on the phone. The Cast device can't send the custom user-agent/referrer some
    streams need, and streams without CORS headers are refused. If a link fails, the next one is tried.
  - Stopping the cast moves playback back to the phone.
- Material 3 design with light and dark themes.
- Min SDK 24, target/compile SDK 37 (Android 17).

## Project structure

```
tv_app/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/…                 Gradle 9.8.1 wrapper
└── app/
    ├── build.gradle.kts             AGP 9.4.1, Media3 1.11.1, Material 1.14, Coil 2.7
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/livetv/app/
        │   │   ├── Channel.kt              data model
        │   │   ├── M3uParser.kt            M3U parsing
        │   │   ├── ChannelMerger.kt        merges quality feeds + backup links
        │   │   ├── PlaylistRepository.kt   download + disk cache
        │   │   ├── Prefs.kt                URLs, favorites, quality, dim option
        │   │   ├── MainViewModel.kt        ExoPlayer + Cast playback, filtering, favorites, timeouts/errors
        │   │   ├── MainActivity.kt         UI, fullscreen, gestures, HDMI/Presentation, remote
        │   │   ├── TvPresentation.kt       fullscreen video on the external display
        │   │   ├── ChannelAdapter.kt       RecyclerView adapter (headers + rows)
        │   │   ├── CastOptionsProvider.kt  Google Cast setup (Default Media Receiver)
        │   │   └── SettingsActivity.kt     playlist + backup URLs, dim option
        │   └── res/                        layouts, icons, themes (values + values-night)
        └── test/…/                         M3uParserTest.kt, ChannelMergerTest.kt
```

## Get the code

```bash
git clone https://github.com/Yugesh-KC/Live-TV.git
cd Live-TV
```

`local.properties` (your SDK path) is not in the repo. Android Studio creates it on the first sync.
From a terminal, create it yourself or set `ANDROID_HOME`:

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

Run the unit tests with `./gradlew test`.

## Build the APK in Android Studio

1. Install the **latest stable Android Studio**. The project uses AGP 9.4, which older versions such as 2025.1 can't open.
   Update via *Help → Check for Updates*.
2. Choose *File → Open* and select the cloned project folder. Wait for the Gradle sync to finish.
   - If Studio asks for the SDK, install **Android SDK Platform 37** in *Tools → SDK Manager*.
3. Choose *Build → Generate App Bundles or APKs → Generate APKs*.
4. When it finishes, click **locate**. The APK is in `app/build/outputs/apk/debug/app-debug.apk`.
   For a release build, run `./gradlew assembleRelease` to get `app/build/outputs/apk/release/app-release.apk`.

From a terminal:

```bash
./gradlew assembleRelease        # or assembleDebug
```

### Signing

Without extra setup, release builds are signed with the local debug key. That's fine for installing on your own phone.
To use your own key, create `keystore.properties` in the project root:

```
storeFile=my-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

`keystore.properties` and `*.jks` files are gitignored. Never commit them.

## Install on your phone

Download the latest APK from the [Releases](https://github.com/Yugesh-KC/Live-TV/releases) page,
or build one yourself as described above.

**Option A: copy the file**
1. Copy `app-release.apk` to the phone (USB, Google Drive, Telegram, etc.).
2. Open it in the phone's Files app.
3. When asked, allow *Install unknown apps* for that app, then tap **Install**.

**Option B: USB debugging**
1. On the phone, go to *Settings → About phone* and tap **Build number** 7 times. Then open *Developer options* and turn on **USB debugging**.
2. Connect the phone with USB and accept the prompt on the phone.
3. Run `adb install -r app/build/outputs/apk/release/app-release.apk`, or press ▶ Run in Android Studio.

## Using it with a TV

Plug a USB-C to HDMI adapter or cable into the phone and the TV. The phone must support video
output over USB-C (DisplayPort Alt Mode). Many Samsung Galaxy S/Note, Pixel 8 and newer, and some
Motorola, Asus and Xiaomi phones do. Most budget phones don't.

- If the phone exposes the TV as a second screen, video goes fullscreen on the TV and the phone becomes the remote.
- If the phone can only mirror its screen, the app switches to fullscreen landscape so the mirrored picture fills the TV.
- On Samsung phones, turn DeX off (or choose "Screen mirroring" in the HDMI popup) to get the Presentation mode.

## Notes

- Streams in public playlists come and go. Many channels are geo-blocked or offline, which is what the "Channel unavailable" message is for.
- The app only plays streams from the playlist URL you configure. It hosts no content.
