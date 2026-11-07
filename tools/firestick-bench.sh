#!/usr/bin/env bash
# Measure how fast Home returns to the launcher on a Fire TV Stick, and how much memory is free.
# For each run: open an app, wait, press Home, and read from logcat the time from Amazon's home starting
# (ActivityManager START ... HomeActivity) to FireHome's "launcher shown". Prints each run, the median and MemAvailable.
#
# Usage: tools/firestick-bench.sh <stick ip[:port]> [runs] [app package]
set -euo pipefail
target=${1:?stick ip}; [[ "$target" == *:* ]] || target="$target:5555"
runs=${2:-5}; app=${3:-com.amazon.firetv.youtube}
export ANDROID_SERIAL="$target"
adb connect "$target" >/dev/null

ms() { # HH:MM:SS.mmm -> milliseconds
    awk -F'[:.]' '{print (($1*60+$2)*60+$3)*1000+$4}' <<<"$1"
}

times=()
for i in $(seq 1 "$runs"); do
    adb shell monkey -p "$app" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
    sleep 8
    adb logcat -c
    adb shell input keyevent KEYCODE_HOME
    sleep 9
    log=$(adb logcat -d -v time)
    start=$(grep -m1 -E "START u0.*com.amazon.tv.launcher/.ui.Home" <<<"$log" | awk '{print $2}' || true)
    shown=$(grep -m1 "FireHome.*launcher shown" <<<"$log" | awk '{print $2}' || true)
    if [[ -z "$start" || -z "$shown" ]]; then echo "run $i: no reading"; continue; fi
    d=$(( $(ms "$shown") - $(ms "$start") ))
    times+=("$d"); echo "run $i: ${d} ms"
done
if (( ${#times[@]} )); then
    printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END {print "median: " a[int((NR+1)/2)] " ms (min " a[1] ", max " a[NR] ")"}'
fi
adb shell "grep -E 'MemAvailable|MemFree' /proc/meminfo"
echo "amazon processes running: $(adb shell ps -A | grep -c com.amazon)"
