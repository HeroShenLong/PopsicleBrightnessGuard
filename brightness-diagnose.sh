#!/system/bin/sh
# Read device state only. The sole write is a report in Download.
# Usage: su -c 'sh /sdcard/Download/brightness-diagnose.sh cold'
#        su -c 'sh /sdcard/Download/brightness-diagnose.sh hot'

if [ "$(id -u)" != "0" ]; then
    echo "Run this script through su (Root)."
    exit 1
fi

case "$1" in
    cold|hot) BRIGHTNESS_LABEL="$1" ;;
    *) echo "Usage: sh brightness-diagnose.sh cold|hot"; exit 1 ;;
esac

BRIGHTNESS_REPORT="/sdcard/Download/brightness-${BRIGHTNESS_LABEL}-$(date +%Y%m%d-%H%M%S).txt"
if [ ! -d /sdcard/Download ]; then
    echo "Download directory not found. Create it using your file manager."
    exit 1
fi

exec 3>&1
exec > "$BRIGHTNESS_REPORT" 2>&1 || exit 1

echo "=== Snapshot ==="
date
echo "Label: $BRIGHTNESS_LABEL"
for BRIGHTNESS_PROP in ro.product.model ro.product.device ro.build.version.incremental ro.build.version.release ro.build.version.sdk ro.vendor.thermal.dimming.enable; do
    printf '%s=' "$BRIGHTNESS_PROP"
    getprop "$BRIGHTNESS_PROP"
done

echo "=== Selected display settings ==="
for BRIGHTNESS_SETTING in screen_brightness_mode screen_brightness screen_brightness_float; do
    printf '%s=' "$BRIGHTNESS_SETTING"
    settings get system "$BRIGHTNESS_SETTING"
done

echo "=== Display service ==="
dumpsys display

echo "=== Thermal service ==="
dumpsys thermalservice

echo "=== Backlight readings ==="
for BRIGHTNESS_PANEL in /sys/class/backlight/*; do
    [ -d "$BRIGHTNESS_PANEL" ] || continue
    echo "$BRIGHTNESS_PANEL"
    for BRIGHTNESS_NODE in brightness actual_brightness max_brightness; do
        if [ -r "$BRIGHTNESS_PANEL/$BRIGHTNESS_NODE" ]; then
            printf '%s=' "$BRIGHTNESS_NODE"
            cat "$BRIGHTNESS_PANEL/$BRIGHTNESS_NODE"
        fi
    done
done

echo "=== Thermal message interface inventory ==="
ls -l /sys/class/thermal/thermal_message

echo "=== Thermal configuration inventory (names only) ==="
for BRIGHTNESS_CONFIG_DIR in /vendor/etc /odm/etc /product/etc /system/etc /data/vendor/thermal; do
    [ -d "$BRIGHTNESS_CONFIG_DIR" ] || continue
    find "$BRIGHTNESS_CONFIG_DIR" -maxdepth 3 -type f -iname '*thermal*'
done

echo "=== Root module names and state (no module scripts) ==="
for BRIGHTNESS_MODULE in /data/adb/modules/*; do
    [ -f "$BRIGHTNESS_MODULE/module.prop" ] || continue
    echo "$BRIGHTNESS_MODULE"
    if [ -f "$BRIGHTNESS_MODULE/disable" ]; then echo "disabled=yes"; else echo "disabled=no"; fi
    if [ -f "$BRIGHTNESS_MODULE/remove" ]; then echo "pending_remove=yes"; fi
    sed -n '/^id=/p; /^name=/p; /^version=/p' "$BRIGHTNESS_MODULE/module.prop"
done

echo "=== End ==="
printf 'Saved report: %s\n' "$BRIGHTNESS_REPORT" >&3
