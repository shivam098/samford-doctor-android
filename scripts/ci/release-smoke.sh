#!/usr/bin/env bash
# Smoke-runs the R8 release APK on a connected emulator. Passes only if the app is running, never
# crashed or stopped responding, and its sign-in screen lists doctors.
#
# Other apps' "isn't responding" dialogs (seen on CI: Pixel Launcher) are dismissed with "Wait" and
# the app is brought back to the front before the screen is read again; an ANR of this app is a
# failure. Every failure reason is appended to <out>/release.failed.
#
# Usage: release-smoke.sh <apk> <out dir>
set -u

apk=$1
out=$2
pkg=com.samford.doctor
attempts=${SMOKE_ATTEMPTS:-8}
interval=${SMOKE_INTERVAL_S:-8}
mkdir -p "$out"

fail() {
    echo "release smoke: $1" | tee -a "$out/release.failed"
}

bring_to_front() {
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$pkg/.MainActivity" >/dev/null 2>&1 || true
}

# Writes the current UI hierarchy to $1; returns non-zero if it could not be read.
dump_ui() {
    adb shell rm -f /sdcard/release-ui.xml >/dev/null 2>&1
    adb shell uiautomator dump /sdcard/release-ui.xml >/dev/null 2>&1 || return 1
    adb pull /sdcard/release-ui.xml "$1" >/dev/null 2>&1 || return 1
    [ -s "$1" ]
}

# Handles an "isn't responding" dialog in UI dump $1. Returns 0 if one was dismissed.
dismiss_anr_dialog() {
    local ui=$1 title bounds x1 y1 x2 y2
    grep -q "responding" "$ui" || return 1
    title=$(grep -oE 'text="[^"]*responding[^"]*"' "$ui" | head -n 1)
    echo "release smoke: system dialog on screen: $title"
    case "$title" in
        *"Samford Doctor"*) fail "the app itself stopped responding ($title)" ;;
    esac
    bounds=$(grep -oE 'text="Wait"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$ui" | head -n 1 | grep -oE '[0-9]+' | tail -n 4 | tr '\n' ' ')
    if [ -n "$bounds" ]; then
        read -r x1 y1 x2 y2 <<<"$bounds"
        adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) >/dev/null 2>&1 || true
    else
        adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    fi
    return 0
}

adb install -r "$apk" || fail "install failed"
adb logcat -c || true
adb shell am start -W -n "$pkg/.MainActivity" || fail "launch failed"

found=0
for attempt in $(seq 1 "$attempts"); do
    sleep "$interval"
    ui="$out/release-ui-$attempt.xml"
    if ! dump_ui "$ui"; then
        echo "release smoke: attempt $attempt could not read the screen"
        continue
    fi
    cp "$ui" "$out/release-ui.xml"
    if dismiss_anr_dialog "$ui"; then
        sleep 2
        bring_to_front
        continue
    fi
    if grep -Eq 'Demo doctor A|Asha Verma' "$ui"; then
        found=1
        echo "release smoke: sign-in screen with doctors after attempt $attempt"
        break
    fi
    # Something else is in front (another app, a system window): bring the app back and look again.
    bring_to_front
done

adb shell screencap -p /sdcard/release.png >/dev/null 2>&1 && adb pull /sdcard/release.png "$out/release-smoke.png" >/dev/null 2>&1
adb logcat -d >"$out/release-logcat.txt" 2>/dev/null || true

adb shell pidof "$pkg" >"$out/release-pid.txt" 2>/dev/null || fail "the app is not running"
grep -q "Process: $pkg, PID" "$out/release-logcat.txt" && fail "the app crashed (Java exception in its process)"
grep -Eq "Cmdline: ${pkg//./\\.}[[:space:]]*$" "$out/release-logcat.txt" && fail "the app crashed (native signal)"
grep -Eq "ANR in ${pkg//./\\.}([[:space:]]|$)" "$out/release-logcat.txt" && fail "the app stopped responding (ANR in logcat)"
[ "$found" = 1 ] || fail "the sign-in screen did not list doctors after $attempts attempts"

adb uninstall "$pkg" >/dev/null 2>&1 || true
[ ! -f "$out/release.failed" ]
