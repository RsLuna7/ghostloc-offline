#!/system/bin/sh
# GhostLoc watchdog (Magisk service.d).
#
# 1. Checkpoints the Vector config WAL so manager-side writes stay visible
#    to the daemon (only effective when /system/bin/sqlite3 exists).
# 2. Boot-time self-heal for the one failure mode that otherwise looks like
#    "module enabled everywhere but the spoof is dead": after an in-place
#    APK update HyperOS moves /data/app directories, and Vector's
#    systemServerModuleRows() resolves the system_server injection list from
#    the path stored in modules_config.db instead of PackageManager, so the
#    module loads in every process EXCEPT system_server. Detect that state
#    from Vector's own module log, re-resolve the path via
#    disable/enable, and bounce system_server once — no manual steps.

MOD=dev.ghostloc.offline
CLI=/data/adb/lspd/cli
LOG_TAG=GhostLocWatchdog

n=0
while [ ! -x "$CLI" ] && [ "$n" -lt 60 ]; do
    sleep 2
    n=$((n + 1))
done
[ -x "$CLI" ] || exit 0

# --- 1. WAL checkpoint loop (kept from 0.4.x) ---
(
    while true; do
        sqlite3 /data/adb/lspd/config/modules_config.db 'PRAGMA wal_checkpoint(PASSIVE);' >/dev/null 2>&1
        sleep 15
    done
) >/dev/null 2>&1 &

# --- 2. system_server load watchdog ---
# Give the framework time to finish its module-loading window, then judge
# from Vector's own per-process load record for the running system_server pid.
n=0
while [ "$n" -lt 45 ]; do
    SSP=$(pidof system_server)
    if [ -n "$SSP" ] && grep -q " $SSP: .*Loaded module $MOD successfully" \
        /data/adb/lspd/log/modules_*.log 2>/dev/null; then
        log -t "$LOG_TAG" "module active in system_server ($SSP)"
        exit 0
    fi
    sleep 2
    n=$((n + 1))
done

SSP=$(pidof system_server)
[ -n "$SSP" ] || exit 0
if grep -q " $SSP: .*Loaded module $MOD successfully" \
    /data/adb/lspd/log/modules_*.log 2>/dev/null; then
    exit 0
fi

log -t "$LOG_TAG" "module missing from system_server ($SSP); re-resolving path and bouncing"
"$CLI" modules disable "$MOD" >/dev/null 2>&1
"$CLI" modules enable "$MOD" >/dev/null 2>&1
sleep 2
killall system_server
log -t "$LOG_TAG" "system_server bounced to load $MOD"
