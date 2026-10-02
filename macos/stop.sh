#!/bin/bash
set -eu
BASE="$HOME/Library/Application Support/IPCameraMonitor"
PIDFILE="$BASE/data/monitor.pid"
[ -f "$PIDFILE" ] || exit 0
PID="$(cat "$PIDFILE")"
case "$PID" in ''|*[!0-9]*) echo 'Geçersiz PID; işlem durdurulmadı.' >&2; exit 2 ;; esac
CMD="$(ps -p "$PID" -o command= 2>/dev/null || true)"
case "$CMD" in
    *IPCameraMonitor-J.3.3.jar*)
        # TERM, Java kapanış kancasının durum dosyasını kaydetmesine izin verir.
        kill -TERM "$PID"
        for n in $(seq 1 20); do
            kill -0 "$PID" 2>/dev/null || exit 0
            sleep 1
        done
        echo 'Program henüz kapanmadı; veri kaybını önlemek için zorla durdurulmadı.' >&2
        exit 1 ;;
    '') rm -f "$PIDFILE" "$BASE/data/monitor.port" ;;
    *) echo 'PID başka bir sürece ait; işlem durdurulmadı.' >&2; exit 2 ;;
esac
