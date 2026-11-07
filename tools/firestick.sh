#!/usr/bin/env bash
# Install, update or undo this launcher on a Fire TV Stick (Fire OS 7) without root. Amazon's home stays
# installed and enabled: FireHome (inside HomeKeyService) covers it and brings this launcher back.
#
#   tools/firestick.sh install <stick ip>   build, install, turn on the Home service, open the launcher
#   tools/firestick.sh theme <stick ip> <theme folder>   push a theme (config.json + wallpaper.jpg)
#   tools/firestick.sh status <stick ip>    show whether the Home service is on
#   tools/firestick.sh undo <stick ip> [--uninstall]   turn the Home service off (and remove the app)
#
# Needs adb, and for install JDK 17 + Android SDK 34 (JAVA_HOME / ANDROID_HOME, or Homebrew's defaults).
# On the stick: Settings > My Fire TV > About, press OK 7 times on the device name, then
# Settings > My Fire TV > Developer options > ADB debugging ON. Accept the prompt on the TV the first time.
set -euo pipefail

PKG=com.gathin.tvhome
SERVICE=$PKG/$PKG.HomeKeyService
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/launcher/app/build/outputs/apk/release/app-release.apk"

cmd=${1:-}; ip=${2:-}
if [[ -z "$cmd" || -z "$ip" ]]; then sed -n '2,13p' "$0"; exit 2; fi
target="$ip"; [[ "$target" == *:* ]] || target="$ip:5555"

adb connect "$target" >/dev/null
sh() { adb -s "$target" shell "$@"; }

enabled_list() { sh settings get secure enabled_accessibility_services | tr -d '\r'; }

service_on() {
    local cur; cur=$(enabled_list)
    case ":$cur:" in *":$SERVICE:"*) return 0 ;; esac
    if [[ -z "$cur" || "$cur" == "null" ]]; then cur="$SERVICE"; else cur="$cur:$SERVICE"; fi
    sh settings put secure enabled_accessibility_services "$cur"
    sh settings put secure accessibility_enabled 1
}

service_off() {
    local cur next; cur=$(enabled_list)
    next=$(printf '%s' "$cur" | tr ':' '\n' | grep -v -x "$SERVICE" | paste -sd: - || true)
    if [[ -z "$next" ]]; then
        sh settings delete secure enabled_accessibility_services >/dev/null
        sh settings put secure accessibility_enabled 0
    else
        sh settings put secure enabled_accessibility_services "$next"
    fi
}

case "$cmd" in
install)
    model=$(sh getprop ro.product.model | tr -d '\r'); fireos=$(sh getprop ro.build.version.name | tr -d '\r')
    sdk=$(sh getprop ro.build.version.sdk | tr -d '\r')
    echo "Stick: $model, $fireos (API $sdk)"
    if (( sdk < 26 )); then echo "This launcher needs Fire OS 7 or newer (API 26+)."; exit 1; fi
    export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
    export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
    (cd "$ROOT/launcher" && ./gradlew --no-daemon -q assembleRelease)
    echo "Installing (accept on the TV if Fire OS asks)..."
    adb -s "$target" install -r "$APK"
    # Lets the launcher put its Home service back after a force stop (restore_key_services). Harmless if refused.
    sh pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
    service_on
    sh am start -n $PKG/.MainActivity >/dev/null
    echo "Done. Press Home on the real remote to check: you should land back here."
    echo "Amazon's home screen: launcher Settings (gear) > Amazon home screen."
    ;;
theme)
    dir=${3:?theme folder}
    [[ -f "$dir/config.json" ]] || { echo "no config.json in $dir"; exit 2; }
    dest=/sdcard/Android/data/$PKG/files
    sh mkdir -p "$dest"
    adb -s "$target" push "$dir/config.json" "$dest/config.json" >/dev/null
    for f in wallpaper.jpg wallpaper.png; do
        [[ -f "$dir/$f" ]] && adb -s "$target" push "$dir/$f" "$dest/$f" >/dev/null
    done
    [[ -d "$dir/wallpapers" ]] && adb -s "$target" push "$dir/wallpapers" "$dest/" >/dev/null
    sh am force-stop $PKG
    sleep 2      # a force stop takes the Home service off the enabled list, asynchronously
    service_on
    sh am start -n $PKG/.MainActivity >/dev/null
    echo "Theme pushed from $dir."
    ;;
status)
    case ":$(enabled_list):" in *":$SERVICE:"*) echo "Home service: on" ;; *) echo "Home service: off" ;; esac
    sh dumpsys package $PKG | grep -m1 versionName || echo "launcher not installed"
    ;;
undo)
    service_off
    echo "Home service off: Home goes to Amazon's home screen again."
    if [[ "${3:-}" == "--uninstall" ]]; then adb -s "$target" uninstall $PKG; fi
    ;;
*)
    sed -n '2,13p' "$0"; exit 2 ;;
esac
