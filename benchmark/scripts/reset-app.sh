#!/system/bin/sh
set -eu
[ "$(getprop ro.kernel.qemu)" = "1" ] || exit 21
[ "$(id -u)" = "0" ] || exit 22
STYLE="$1"
case "$STYLE" in SHUFFLE|DRAW) ;; *) exit 23 ;; esac
APP_DIR=/data/user/0/com.tabula.v3
UID_NUMBER=$(cmd package list packages -U com.tabula.v3 | sed -n 's/^package:com\.tabula\.v3 uid://p')
[ -n "$UID_NUMBER" ] || exit 24
am force-stop com.tabula.v3
# Only the synthetic emulator app's private state is reset; MediaStore fixtures are retained.
rm -rf "$APP_DIR/cache" "$APP_DIR/code_cache" "$APP_DIR/files" "$APP_DIR/shared_prefs"
mkdir -p "$APP_DIR/shared_prefs"
cp "/data/local/tmp/tabula-perf/prefs-$STYLE.xml" "$APP_DIR/shared_prefs/tabula_prefs.xml"
chown -R "$UID_NUMBER:$UID_NUMBER" "$APP_DIR/shared_prefs"
chmod 700 "$APP_DIR/shared_prefs"
chmod 600 "$APP_DIR/shared_prefs/tabula_prefs.xml"
restorecon -RF "$APP_DIR/shared_prefs"
echo RESET_OK
