# Fire TV Stick (Fire OS 7) fork

A personal fork of CleanYourTV that makes the launcher the home screen on a Fire TV Stick without root.
Tested on a Fire TV Stick 3rd gen (Fire OS 7.7.1.7, Android 9). Amazon's home screen stays installed and enabled.

## How Home works

Fire OS won't let another app be Home, never passes the Home key to accessibility services, and holds back
activity starts from background apps for 5 s after Home. `FireHome` (inside `HomeKeyService`, on when
`stock_home_redirect` is `com.amazon.tv.launcher`) works around that:

- Amazon's `HOME_PRESSED` broadcast, or the window event for Amazon's `HomeActivity`, shows a cover: a full-resolution
  snapshot of the launcher's last frame in an accessibility overlay.
- Behind it, the launcher's own task is moved to the front off the main thread (the move can block for seconds),
  retried every 300 ms until it sticks; with no live task, an exact alarm plus a direct start.
- D-pad and OK pressed under the cover are queued and replayed into the launcher when it takes over. Home keeps
  the launcher's place, so the real screen matches the snapshot. The cover fails open after 6 s.
- `FireHomePolicy` (unit tested) leaves Amazon's settings alone, has a settings grace, a loop guard, and the
  "Amazon home screen" pause in Launcher settings (until Home or another app).

Ideas from FireFrame, the bearylabs/hrudek `firetv` fork and andrewsaad/fire-tv-cleanup (all MIT).

## Use

```sh
tools/firestick.sh install <stick ip>                       # build, install, turn on the Home service
tools/firestick.sh theme <stick ip> themes/cleanframe-look   # push a theme
tools/firestick.sh undo <stick ip> [--uninstall]             # Home back to Amazon
tools/firestick-bench.sh <stick ip> 5                        # time Home from an app back to the launcher
```

On macOS, if `adb connect` says "No route to host" while the port is open, run `python3 tools/relay.py <stick ip>`
and use `127.0.0.1:15555` (see `tools/native-adb.md`). Always reinstall through `firestick.sh install`: a plain
`adb install -r` can leave the Home service off.

## Themes

- `themes/cleanframe-look`: a CleanFrame-inspired single row on a glass dock, with seven original abstract
  wallpapers drawn by `make_wallpapers.py` (moss is the default; pick others from Launcher settings > Wallpaper).
- `themes/my-firestick`: a plainer dock and grid.

## Screensaver without ads

Amazon's screensaver (`com.amazon.ftv.screensaver`) shows sponsored content. The launcher's own `ClockDream` replaces
it; with `clock_wallpaper` (on in `themes/cleanframe-look`) the clock drifts over the theme's wallpaper:

```sh
adb shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream
# undo: adb shell settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
```

## Speed notes (Fire TV Stick 3rd gen)

Home from an app back to the launcher, median: 2.46 s stock; 2.34 s after the
`examples/amazon-fire-tv-stick-2020` debloat; 1.76 s with Featured Content video/audio autoplay also off
(`settings put global com.amazon.tv.settings.featured_item_video_enabled 0`). The remaining wait is Amazon's
home screen resuming, which Fire OS requires before another task can come to the front.
