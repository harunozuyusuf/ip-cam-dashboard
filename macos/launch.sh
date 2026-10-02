#!/bin/bash
set -eu

# Yazılabilir kullanıcı verileri .app dışında kalır; yeni paket eski veriyi silmez.
CONTENTS="$(cd "$(dirname "$0")/.." && pwd)"
RES="$CONTENTS/Resources"
BASE="$HOME/Library/Application Support/IPCameraMonitor"
JAR="$RES/IPCameraMonitor-J.3.3.jar"
mkdir -p "$BASE/data"

fail() {
    printf '%s\n' "$1" >&2
    /usr/bin/osascript - "$1" <<'APPLESCRIPT' || true
on run argv
    display alert "IP Kamera Monitor" message (item 1 of argv) as critical
end run
APPLESCRIPT
    exit 1
}

case "$(uname -m)" in
    arm64) JAVA="$RES/runtime-arm64/Contents/Home/bin/java" ;;
    x86_64) JAVA="$RES/runtime-x64/Contents/Home/bin/java" ;;
    *) fail 'Bu paket Intel ve Apple Silicon Mac içindir.' ;;
esac
[ -x "$JAVA" ] || fail 'Gömülü Java bulunamadı. ZIP dosyasını tamamen açın.'

# PID dosyası başka bir sürece aitse onu sonlandırmadan eski durum dosyasını temizle.
if [ -f "$BASE/data/monitor.pid" ]; then
    PID="$(cat "$BASE/data/monitor.pid")"
    case "$PID" in ''|*[!0-9]*) fail 'Geçersiz PID kaydı. data/monitor.pid dosyasını kontrol edin.' ;; esac
    CMD="$(ps -p "$PID" -o command= 2>/dev/null || true)"
    case "$CMD" in
        *IPCameraMonitor-J.3.3.jar*)
            PORT="$(cat "$BASE/data/monitor.port" 2>/dev/null || true)"
            case "$PORT" in ''|*[!0-9]*) fail 'Program çalışıyor fakat port bilgisi hazır değil.' ;; esac
            [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || fail 'Geçersiz port kaydı.'
            /usr/bin/open "http://127.0.0.1:$PORT"
            exit 0 ;;
    esac
    rm -f "$BASE/data/monitor.pid" "$BASE/data/monitor.port"
fi

# Aynı anda iki başlatma isteğinin farklı Java süreçleri oluşturmasını önler.
LOCK="$BASE/.launch-lock"
mkdir "$LOCK" 2>/dev/null || fail 'Başlatma işlemi zaten sürüyor. Önceki açılış yarıda kaldıysa uygulamanın kapalı olduğunu doğrulayıp .launch-lock klasörünü kaldırın.'
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
trap 'exit 1' HUP INT TERM
cp "$RES/ui.html" "$BASE/ui.html"
cd "$BASE"
# Tanılama dosyası her açılışta yenilenir; günlük olaylar data/logs içinde kalır.
nohup "$JAVA" -Djava.awt.headless=true -jar "$JAR" >"$BASE/launcher.log" 2>&1 &
CHILD=$!
for n in $(seq 1 30); do
    if ! kill -0 "$CHILD" 2>/dev/null; then
        fail 'Program açılamadı. Library/Application Support/IPCameraMonitor/launcher.log dosyasını kontrol edin.'
    fi
    if [ -f "$BASE/data/monitor.port" ]; then
        PORT="$(cat "$BASE/data/monitor.port")"
        case "$PORT" in ''|*[!0-9]*) fail 'Geçersiz port kaydı.' ;; esac
        [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || fail 'Geçersiz port kaydı.'
        /usr/bin/open "http://127.0.0.1:$PORT"
        exit 0
    fi
    sleep 1
done
fail 'Başlatma beklenenden uzun sürdü. launcher.log dosyasını kontrol edin; Java süreci çalışmaya devam ediyor olabilir.'
