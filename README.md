# Radio

A small Android app that shows [radio.pieter.com](https://radio.pieter.com) full screen and plays its
live stream. It runs on Android phones and tablets and on Android / Google TV (built and tested on a
TCL Smart TV Pro).

The app is a WebView around the real website, plus a small overlay that adapts the page to a TV or
phone screen.

## Features

- Opens radio.pieter.com full screen and starts the stream automatically (no "tune in" click needed)
- Keeps the screen on, and keeps playing when you switch to another app
- Shows up on the Android TV home screen (with a banner) as well as the phone app drawer
- Back button goes back in the page history, then exits

### Customizations on top of the website

The app injects [`app/src/main/assets/inject.html`](app/src/main/assets/inject.html) into the page.
It adds:

| Change | Details |
|---|---|
| **Toolbar** (bottom-left) | Styled like the site's own Skins button. Reachable with the remote's arrow keys. |
| **◉ Clear view** | Hides Winamp, labels and the schedule so only the animated background is visible. Press again to bring everything back. The music keeps playing. |
| **Skins** | The site's own Winamp skin picker, moved into the toolbar. |
| **2× / 1×** | On wide screens the site uses Winamp's Double Size mode. The app keeps Winamp at normal size, stacked in the bottom-left corner, unless you switch to 2×. Remembered. |
| **− / +** | Page size, from 50% to 150% in 10% steps (default 80%). Remembered. |
| **No chat** | The site's chat is hidden. |
| **Remote center / OK button** | Switches to the next background video (the site's own rotation). If a toolbar button or the skin picker has focus, it presses that instead. |

## Build

Requirements: JDK 17+ and the Android SDK (platform 34, build-tools 36.0.0). Create
`local.properties` pointing at your SDK:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

Then build:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

**Over adb** (phone with USB debugging, or a TV with network debugging enabled):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If adb reports more than one device (a TV is often listed twice: by IP and by wireless-debugging
name), pick one with `-s`:

```bash
adb -s 192.168.1.22:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

**Without adb:** copy the APK to the device (USB stick, cloud drive, or a "send files to TV" app),
open it with a file manager and allow installing unknown apps.

## How it works

- [`MainActivity.java`](app/src/main/java/com/pieter/radio/MainActivity.java) hosts the WebView. It
  fetches the radio page itself, removes the site's viewport tag and inserts `inject.html` at the
  top of `<head>`, so the overlay runs before the site's own scripts.
- Page size is applied with a viewport of `screen width ÷ scale`. An explicit width is needed
  because Android TV's WebView ignores a scale-only viewport.
- The `−`/`+` buttons call `AppBridge.setScale()`, which saves the scale and reloads the page.
- The remote's center/OK key is caught in `dispatchKeyEvent` and forwarded to the page's
  `appCenterButton()`.
- Debug builds enable WebView remote debugging (`chrome://inspect`).

## Known issues

- Switching backgrounds can cause a brief stutter in the audio on the TV (under investigation).
- YouTube's title bar briefly appears over the background when a new video starts.
- If radio.pieter.com changes its layout or script names (`startRadio`, `layoutWindows`, `bgLoad`,
  `nextBgVid`), parts of the overlay may stop working. If the page can't be fetched, the app falls
  back to the plain website.
