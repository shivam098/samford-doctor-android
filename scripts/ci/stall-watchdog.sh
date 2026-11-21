#!/usr/bin/env bash
# Collects evidence if an instrumented-test step stalls instead of letting it hang silently.
# Started in the background before a gradle step; the step touches <out>/<label>.done when it ends.
# After <delay> seconds (default 900) without that file, and again every STALL_REPEAT_S (600) after,
# it saves the app's Java thread stacks (debuggerd -j, when the device allows it; the app's own
# StallDumpRule writes them to logcat regardless), meminfo, activity state, a screenshot and the
# logcat tail to <out>/stall-<label>-<n>/.
#
# Usage: stall-watchdog.sh <label> <out dir> [delay seconds]
set -u
label=$1
out=$2
delay=${3:-900}
repeat_s=${STALL_REPEAT_S:-600}
pkg=${STALL_PKG:-com.samford.doctor.debug}
done_file="$out/$label.done"
mkdir -p "$out"

waited=0
next=$delay
n=0
while [ ! -f "$done_file" ]; do
    sleep 10
    waited=$((waited + 10))
    [ "$waited" -lt "$next" ] && continue
    n=$((n + 1))
    dir="$out/stall-$label-$n"
    mkdir -p "$dir"
    echo "stall watchdog: '$label' still running after ${waited}s; evidence in $dir"
    pid=$(adb shell pidof "$pkg" 2>/dev/null | tr -d '\r')
    echo "${pid:-not running}" >"$dir/pid.txt"
    if [ -n "$pid" ]; then
        adb shell debuggerd -j "$pid" >"$dir/java-stacks.txt" 2>&1 ||
            adb shell run-as "$pkg" debuggerd -j "$pid" >>"$dir/java-stacks.txt" 2>&1 || true
        adb shell dumpsys meminfo "$pkg" >"$dir/meminfo.txt" 2>&1 || true
    fi
    adb shell dumpsys activity activities >"$dir/activities.txt" 2>&1 || true
    adb shell dumpsys activity processes "$pkg" >"$dir/processes.txt" 2>&1 || true
    adb shell screencap -p /sdcard/stall.png >/dev/null 2>&1 && adb pull /sdcard/stall.png "$dir/screen.png" >/dev/null 2>&1
    adb logcat -d -t 4000 >"$dir/logcat-tail.txt" 2>&1 || true
    next=$((next + repeat_s))
done
echo "stall watchdog: '$label' finished after about ${waited}s"
