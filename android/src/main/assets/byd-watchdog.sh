# Copyright (c) Tailpark contributors
# SPDX-License-Identifier: BSD-3-Clause
# PKG, TOKEN, DIR and IDENTITY are supplied by the APK's installer.
umask 077
SCRIPT="$DIR/watchdog.sh"
LOCK="$DIR/watchdog.lock"
LOG="$DIR/helper.log"
CHILD=""
OWNED=0
VERSION=$(cat "$DIR/version" 2>/dev/null)
PROCESS="tsbyd-helper:$PKG"

clock() {
    read UPTIME_VALUE UPTIME_IDLE < /proc/uptime
    printf '%s' "${UPTIME_VALUE%%.*}"
}

named_process() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$1" 2>/dev/null || return 1
    tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null | grep -Fx "$2" >/dev/null
}

stop_child() {
    if [ -n "$CHILD" ]; then
        LEFT=3
        while kill -0 "$CHILD" 2>/dev/null && [ "$LEFT" -gt 0 ]; do
            # A recycled PID must never make us signal an unrelated process.
            if named_process "$CHILD" "$PROCESS"; then kill "$CHILD" 2>/dev/null; fi
            sleep 1
            LEFT=$((LEFT - 1))
        done
        if named_process "$CHILD" "$PROCESS"; then kill -KILL "$CHILD" 2>/dev/null; fi
        if [ "$OWNED" -eq 1 ] && ! kill -0 "$CHILD" 2>/dev/null; then wait "$CHILD" 2>/dev/null; fi
        CHILD=""
    fi
}

cleanup() {
    trap - EXIT HUP INT TERM
    stop_child
    if [ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ]; then
        rm -f "$LOCK/pid"
        rmdir "$LOCK" 2>/dev/null
    fi
}

if ! mkdir "$LOCK" 2>/dev/null; then
    OLD=$(cat "$LOCK/pid" 2>/dev/null)
    if named_process "$OLD" "$SCRIPT"; then exit 0; fi
    # An owner may be between mkdir and publishing its PID.
    sleep 2
    OLD=$(cat "$LOCK/pid" 2>/dev/null)
    if named_process "$OLD" "$SCRIPT"; then exit 0; fi
    rm -f "$LOCK/pid"
    rmdir "$LOCK" 2>/dev/null || exit 1
    mkdir "$LOCK" 2>/dev/null || exit 1
fi
printf '%s\n' "$$" > "$LOCK/pid"
trap 'cleanup; exit 0' HUP INT TERM
trap cleanup EXIT
printf '[%s] Watchdog started\n' "$(date -u)" >> "$LOG"

RETRIES=0
while [ ! -f "$DIR/disabled" ]; do
    # Each iteration resolves current code; never pin a removed APK path.
    APK=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
    if [ -z "$APK" ]; then break; fi
    START=$(clock)
    NEXT_CHECK=$((START + 15))
    CHILD=$(cat "$DIR/helper.pid" 2>/dev/null)
    OWNED=0
    if named_process "$CHILD" "$PROCESS"; then
        if [ "$(cat "$DIR/helper.apk" 2>/dev/null)" != "$APK" ] ||
           [ "$(cat "$DIR/helper.identity" 2>/dev/null)" != "$IDENTITY" ]; then
            stop_child
        fi
    else
        CHILD=""
    fi
    if [ -z "$CHILD" ]; then
        rm -f "$DIR/heartbeat"
        CLASSPATH="$APK" app_process /system/bin --nice-name="$PROCESS" \
            com.tailscale.ipn.byd.BydPowerHelper "$PKG" "$TOKEN" >> "$LOG" 2>&1 < /dev/null &
        CHILD=$!
        OWNED=1
        printf '[%s] Helper launched\n' "$(date -u)" >> "$LOG"
    fi
    UPDATE=0
    while kill -0 "$CHILD" 2>/dev/null; do
        if [ -f "$DIR/disabled" ]; then exit 0; fi
        NOW=$(clock)
        if [ "$NOW" -ge "$NEXT_CHECK" ]; then
            NEXT_CHECK=$((NOW + 15))
            SIZE=$(wc -c < "$LOG" 2>/dev/null)
            [ "${SIZE:-0}" -gt 5242880 ] && : > "$LOG"
            CURRENT=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1)
            if [ -z "$CURRENT" ]; then exit 0; fi
            if [ "$(cat "$DIR/version" 2>/dev/null)" != "$VERSION" ]; then
                UPDATE=1
                break
            fi
            if [ "$CURRENT" != "$APK" ]; then
                printf '[%s] APK changed; reloading helper\n' "$(date -u)" >> "$LOG"
                break
            fi
            BEAT_PID=""
            BEAT_TIME=""
            read BEAT_PID BEAT_TIME < "$DIR/heartbeat" 2>/dev/null
            case "$BEAT_TIME" in ''|*[!0-9]*) BEAT_TIME=$START ;; esac
            if [ "$BEAT_PID" != "$CHILD" ]; then BEAT_TIME=$START; fi
            if [ "$BEAT_TIME" -gt "$NOW" ]; then BEAT_TIME=$START; fi
            if [ "$NOW" -ge "$BEAT_TIME" ] && [ $((NOW - BEAT_TIME)) -gt 90 ]; then
                printf '[%s] Helper heartbeat stale; restarting\n' "$(date -u)" >> "$LOG"
                break
            fi
        fi
        sleep 5
    done
    stop_child
    if [ -f "$DIR/disabled" ]; then exit 0; fi
    if [ "$UPDATE" -eq 1 ]; then
        cleanup
        exec /system/bin/sh "$SCRIPT"
    fi
    NOW=$(clock)
    [ $((NOW - START)) -ge 300 ] && RETRIES=0
    RETRIES=$((RETRIES + 1))
    DELAY=$((RETRIES * 3))
    [ "$DELAY" -gt 60 ] && DELAY=60
    printf '[%s] Helper stopped; retrying in %ss\n' "$(date -u)" "$DELAY" >> "$LOG"
    while [ "$DELAY" -gt 0 ]; do
        [ -f "$DIR/disabled" ] && exit 0
        sleep 1
        DELAY=$((DELAY - 1))
    done
done
