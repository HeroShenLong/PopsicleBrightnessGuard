package io.github.popsicle.brightnessguard;

/** Android-independent gate. Never interprets a user brightness request as a heat limit. */
public final class Policy {
    public static final long MAX_AGE_MS = 12000;
    private boolean heatHold;

    public static boolean targetDevice(String device, String incremental, int sdk) {
        return "popsicle".equalsIgnoreCase(device)
                && incremental != null && incremental.toUpperCase(java.util.Locale.ROOT).startsWith("OS3.")
                && (sdk == 35 || sdk == 36);
    }

    public static int clampLimit(int value) { return Math.max(38, Math.min(48, value)); }

    public synchronized boolean update(double temperature, int threshold) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 80) {
            heatHold = true;
            return false;
        }
        int limit = clampLimit(threshold);
        if (temperature >= limit) heatHold = true;
        else if (temperature <= limit - 2) heatHold = false;
        return !heatHold;
    }

    public static String denyReason(boolean enabled, boolean target, boolean sessionFault,
            boolean mainDisplay, boolean interactive, boolean batteryAllowed,
            int thermalStatus, long sampledAt, long now) {
        if (!enabled) return "observe";
        if (!target) return "unsupported_device_or_rom";
        if (sessionFault) return "session_fault";
        if (!mainDisplay) return "display_not_verified_as_main";
        if (sampledAt <= 0 || now < sampledAt || now - sampledAt > MAX_AGE_MS) return "stale_sample";
        if (!interactive) return "screen_not_interactive";
        if (!batteryAllowed) return "battery_temperature_guard";
        if (thermalStatus < 0 || thermalStatus >= 3) return "system_thermal_guard";
        return "allow";
    }

    public static boolean normalizedCap(double cap) {
        return Double.isFinite(cap) && cap >= 0 && cap < 1;
    }

    public static boolean thermalAdjustment(double requested, double limited) {
        return Double.isFinite(requested) && Double.isFinite(limited)
                && requested > 0 && requested <= 16384 && limited >= 0 && limited < requested;
    }
}
