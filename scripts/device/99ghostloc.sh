#!/system/bin/sh
# GhostLoc state maintenance.
#
# The live state file lives inside the module app's own data dir
# (files/ghostloc-state/state.properties) and is written by system_server
# through the bridge hook; the module app pre-creates the directory with
# world access, so nothing SELinux/label related is needed here.
#
# This script periodically checkpoints the LSPosed config WAL: UI writes that
# go through the libxposed service channel land in the WAL, and the daemon
# only reads the main database at boot, so without checkpoints those writes
# would never become visible to the hook-side remote preferences.
while [ ! -d /data/adb/lspd/config ]; do
    sleep 2
done

(
    while true; do
        sqlite3 /data/adb/lspd/config/modules_config.db 'PRAGMA wal_checkpoint(PASSIVE);' >/dev/null 2>&1
        sleep 15
    done
) >/dev/null 2>&1 &
