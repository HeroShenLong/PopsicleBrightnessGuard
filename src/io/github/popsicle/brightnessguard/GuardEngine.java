package io.github.popsicle.brightnessguard;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Targets verified display heat caps, never the global thermal service or sensor data. */
public final class GuardEngine {
    private static GuardEngine instance;
    private final ClassLoader loader;
    private final HookBridge bridge;
    private final Policy policy = new Policy();
    private final BrightnessMonitor monitor = new BrightnessMonitor();
    private JSONObject brightnessReport = new JSONObject();
    private final boolean target = Policy.targetDevice(Build.DEVICE, Build.VERSION.INCREMENTAL, Build.VERSION.SDK_INT);
    private final Map<Object,Integer> displayIds = Collections.synchronizedMap(new WeakHashMap<>());
    private final List<WeakReference<Object>> controllers = new ArrayList<>();
    private final List<WeakReference<Object>> modifiers = new ArrayList<>();
    private final Map<String,Boolean> installed = new ConcurrentHashMap<>();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private final JSONArray inventory = new JSONArray();
    private final AtomicLong attempts = new AtomicLong(), bypasses = new AtomicLong(), drops = new AtomicLong();
    private final AtomicInteger faults = new AtomicInteger();
    private volatile Context context;
    private volatile String primaryUniqueId = "", lastGate = "observe";
    private volatile boolean enabled, batteryAllowed, interactive;
    private volatile int threshold = 45, thermalStatus = -1;
    private volatile double batteryC = Double.NaN, output = Double.NaN;
    private volatile long sampledAt;
    private volatile boolean sessionFault;
    private volatile long lastThermalAt;
    private volatile double lastThermalCap = Double.NaN;
    private Handler worker;
    private String resetToken = "";
    private boolean previousAllow;
    private long lastEventAt;
    private String lastEventKey = "";

    private GuardEngine(ClassLoader loader, HookBridge bridge) { this.loader = loader; this.bridge = bridge; }

    public static synchronized void install(ClassLoader loader, HookBridge bridge) {
        if (instance != null) return;
        try { instance = new GuardEngine(loader, bridge); instance.start(); }
        catch (Throwable e) { bridge.log("Initialization failed; no additional changes: " + e); }
    }

    private void start() {
        discover("com.android.server.display.DisplayPowerController", 0);
        discover("com.android.server.display.DisplayPowerControllerImpl", 1);
        discover("com.android.server.display.brightness.clamper.BrightnessThermalModifier", 2);
        discover("com.android.server.display.BrightnessThrottler", 3);
        discover("com.android.server.display.ThermalBrightnessController", 4);
        discover("com.android.server.display.ThermalObserver", 4);
        discover("com.android.server.display.TemperatureController", 4);
        discover("com.android.server.display.AutomaticBrightnessControllerImpl", 4);
        discover("com.android.server.display.brightness.clamper.BrightnessClamperController", 5);
        HandlerThread thread = new HandlerThread("PopsicleBrightnessGuard", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.postDelayed(new Runnable() {
            @Override public void run() {
                try { sample(); }
                catch (Throwable e) { fault("sampling", e); }
                worker.postDelayed(this, 1000);
            }
        }, 12000);
        bridge.log("Loaded " + Keys.VERSION + "; target=" + target + "; diagnostic mode until enabled in app.");
    }

    private void discover(String name, int kind) {
        JSONObject item = new JSONObject();
        try {
            Class<?> type = Class.forName(name, false, loader);
            item.put("class", name).put("found", true);
            JSONArray signatures = new JSONArray();
            for (Method method : type.getDeclaredMethods()) {
                String n = method.getName();
                if (method.isSynthetic()) continue;
                if (n.toLowerCase(java.util.Locale.ROOT).contains("thermal") || n.contains("Brightness")
                        || n.equals("apply") || n.equals("applyStateChange") || n.equals("init") || n.equals("clamp")) {
                    if (signatures.length() < 72) signatures.put(method.toGenericString());
                }
            }
            item.put("methods", signatures);
            JSONArray fields = new JSONArray(), constructors = new JSONArray();
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (fields.length() < 96 && (n.contains("brightness") || n.contains("thermal")
                        || n.contains("display") || n.contains("context"))) fields.put(f.toGenericString());
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) constructors.put(c.toGenericString());
            item.put("fields", fields).put("constructors", constructors);
            if (kind <= 3 || name.endsWith("ThermalBrightnessController")) {
                for (Constructor<?> ctor : type.getDeclaredConstructors()) addHook(ctor, new HookBridge.Callback() {
                    @Override public void after(HookBridge.Call call) {
                        if (call.failure == null) remember(call.receiver, call.args, kind);
                    }
                }, "identity");
            }
            if (kind == 1) {
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getName().equals("adjustBrightnessByThermal") && !Modifier.isStatic(m.getModifiers())
                            && m.getReturnType() == float.class && m.getParameterCount() >= 1
                            && m.getParameterTypes()[0] == float.class) {
                        addHook(m, new HookBridge.Callback() {
                            @Override public void after(HookBridge.Call c) {
                                if (c.failure != null || !(c.result instanceof Float) || !(c.args[0] instanceof Float)) return;
                                double requested = ((Number)c.args[0]).doubleValue(), capped = ((Number)c.result).doubleValue();
                                if (!Policy.thermalAdjustment(requested, capped)) {
                                    if(mainDisplay(c.receiver)) monitor.capture(SystemClock.elapsedRealtime(),requested,capped,false,"xiaomi_thermal_input");
                                    return;
                                }
                                boolean allow = allowed(c.receiver);
                                if(mainDisplay(c.receiver)) monitor.capture(SystemClock.elapsedRealtime(),requested,capped,allow,"xiaomi_thermal_input");
                                thermalEvent("xiaomi_adjust", requested, capped, allow, c.receiver);
                                if (allow) { c.replace(c.args[0]); bypasses.incrementAndGet(); }
                            }
                        }, "xiaomi_thermal_adjustment");
                    }
                }
            }
            if (kind == 2) {
                for (Method m : type.getDeclaredMethods()) {
                    boolean apply = m.getName().equals("apply") && m.getReturnType() == void.class
                            && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].getName().equals("android.hardware.display.DisplayManagerInternal$DisplayPowerRequest")
                            && m.getParameterTypes()[1].getName().equals("com.android.server.display.DisplayBrightnessState$Builder");
                    boolean aggregate = m.getName().equals("applyStateChange") && m.getReturnType() == void.class
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getName().equals("com.android.server.display.brightness.clamper.BrightnessClamperController$ModifiersAggregatedState");
                    if (!apply && !aggregate) continue;
                    final String route = apply ? "aosp16_apply" : "aosp16_aggregate";
                    addHook(m, new HookBridge.Callback() {
                        @Override public void before(HookBridge.Call c) {
                            double cap = Reflect.number(c.receiver, "mBrightnessCap");
                            if (!Policy.normalizedCap(cap)) return;
                            boolean allow = allowed(c.receiver);
                            thermalEvent(route, 1, cap, allow, c.receiver);
                            if (allow) { c.skipWith(null); bypasses.incrementAndGet(); }
                        }
                    }, route);
                }
            }
            if (kind == 3) {
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getName().equals("getBrightnessCap") && m.getReturnType() == float.class) {
                        addHook(m, new HookBridge.Callback() {
                            @Override public void after(HookBridge.Call c) {
                                if (c.failure != null || !(c.result instanceof Float)) return;
                                double cap = ((Float)c.result).doubleValue();
                                if (!Policy.normalizedCap(cap)) return;
                                boolean allow = allowed(c.receiver);
                                thermalEvent("aosp_cap_getter", 1, cap, allow, c.receiver);
                                if (allow) { c.replace(1f); bypasses.incrementAndGet(); }
                            }
                        }, "aosp_cap_getter");
                    }
                    if ((m.getName().equals("getBrightnessMaxReason") && m.getReturnType() == int.class)
                            || (m.getName().equals("isThrottled") && m.getReturnType() == boolean.class)) {
                        final boolean reason = m.getName().equals("getBrightnessMaxReason");
                        addHook(m, new HookBridge.Callback() {
                            @Override public void after(HookBridge.Call c) {
                                if (c.failure == null && Policy.normalizedCap(Reflect.number(c.receiver, "mBrightnessCap"))
                                        && Reflect.number(c.receiver, "mBrightnessMaxReason") == 1 && allowed(c.receiver)) {
                                    c.replace(reason ? Integer.valueOf(0) : Boolean.FALSE);
                                }
                            }
                        }, "aosp_reason_consistency");
                    }
                }
            }
            if (kind == 0) {
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getName().equals("updatePowerState") && m.getParameterCount() == 0) {
                        addHook(m, new HookBridge.Callback() {
                            @Override public void after(HookBridge.Call c) {
                                if (c.failure == null) remember(c.receiver, c.args, 0);
                            }
                        }, "display_identity_refresh");
                        try { bridge.deoptimize(m); } catch (Throwable ignored) {}
                    }
                }
            }
            if (kind == 5) for (Method m:type.getDeclaredMethods()) {
                Class<?>[] p=m.getParameterTypes();
                if (!m.getName().equals("clamp") || p.length!=5 || Modifier.isStatic(m.getModifiers())
                        || !m.getReturnType().getName().equals("com.android.server.display.DisplayBrightnessState")
                        || !p[0].getName().equals("com.android.server.display.DisplayBrightnessState")
                        || !p[1].getName().equals("android.hardware.display.DisplayManagerInternal$DisplayPowerRequest")
                        || p[2]!=float.class || p[3]!=boolean.class || p[4]!=int.class) continue;
                addHook(m,new HookBridge.Callback() {
                    @Override public void before(HookBridge.Call c) {
                        if(mainDisplay(c.receiver) && c.args[2] instanceof Float)
                            monitor.capture(SystemClock.elapsedRealtime(),((Float)c.args[2]).doubleValue(),Double.NaN,false,"aosp_clamper_input");
                    }
                },"brightness_request_observer");
            }
            if (kind != 0) for (Method m : type.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.equals("updateThermalBrightness") || n.equals("updateThermalBrightnessIfNeeded")
                        || n.equals("getThermalBrightness")) || Modifier.isStatic(m.getModifiers())) continue;
                final String method = m.toGenericString();
                addHook(m, new HookBridge.Callback() {
                    @Override public void after(HookBridge.Call c) {
                        if (c.failure != null) return;
                        String first = c.args.length > 0 && c.args[0] instanceof Number ? c.args[0].toString() : "none";
                        String result = c.result instanceof Number || c.result instanceof Boolean ? c.result.toString() : "void/unknown";
                        record("vendor_thermal_signal", method + "; arg0=" + first + "; result=" + result
                                + "; main=" + mainDisplay(c.receiver) + "; observation only");
                    }
                }, "diagnostic_signal");
            }
        } catch (ClassNotFoundException e) {
            try { item.put("class", name).put("found", false); } catch (Exception ignored) {}
        } catch (Throwable e) {
            try { item.put("class", name).put("error", e.toString()); } catch (Exception ignored) {}
        }
        inventory.put(item);
    }

    private void addHook(Executable executable, HookBridge.Callback callback, String role) {
        try {
            executable.setAccessible(true);
            bridge.hook(executable, new HookBridge.Callback() {
                @Override public void before(HookBridge.Call c) {
                    try { callback.before(c); }
                    catch (Throwable e) { c.skip = false; c.changed = false; fault(role, e); }
                }
                @Override public void after(HookBridge.Call c) {
                    Object original = c.result; Throwable failure = c.failure;
                    try { callback.after(c); }
                    catch (Throwable e) { c.result = original; c.failure = failure; c.changed = false; fault(role, e); }
                }
            });
            installed.put(executable.toGenericString() + " [" + role + "]",
                    role.equals("xiaomi_thermal_adjustment") || role.equals("aosp_cap_getter")
                    || role.equals("aosp16_apply") || role.equals("aosp16_aggregate"));
        } catch (Throwable e) { bridge.log("Hook not available: " + executable + ": " + e); }
    }

    private void remember(Object object, Object[] args, int kind) {
        if (object == null) return;
        if (context == null) {
            Object c = Reflect.read(object, "mContext");
            if (c instanceof Context) context = (Context)c;
            for (Object arg : args) if (arg instanceof Context) { context = (Context)arg; break; }
        }
        int id = directId(object);
        if (id < 0) for (Object arg : args) { id = directId(arg); if (id >= 0) break; }
        // Xiaomi's no-arg constructor precedes display initialization. Do not cache its default ID=0.
        if (id >= 0 && (kind != 1 || Reflect.read(object,"mDisplayPowerController") != null)) displayIds.put(object, id);
        if (kind == 0 && id == 0) {
            synchronized (controllers) { retainUnique(controllers, object); }
            Object unique = Reflect.read(object, "mUniqueDisplayId");
            if (unique instanceof String && !((String)unique).isEmpty()) primaryUniqueId = (String)unique;
            link(object, id);
        }
        if (kind == 2) synchronized (modifiers) { retainUnique(modifiers, object); }
    }

    private static void retainUnique(List<WeakReference<Object>> list, Object value) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Object old = list.get(i).get();
            if (old == null) list.remove(i);
            else if (old == value) return;
        }
        if (list.size() < 16) list.add(new WeakReference<>(value));
    }

    private void link(Object parent, int id) {
        for (String name : new String[]{"mDisplayPowerControllerImpl", "mBrightnessThrottler", "mThermalBrightnessController", "mBrightnessClamperController"}) {
            Object child = Reflect.read(parent, name);
            if (child != null) displayIds.put(child, id);
        }
    }

    private int directId(Object object) {
        if (object == null) return -1;
        Object parent = Reflect.read(object, "mDisplayPowerController");
        Object parentId = Reflect.read(parent, "mDisplayId");
        if (parentId instanceof Integer) return (Integer)parentId;
        Object id = Reflect.read(object, "mDisplayId");
        if (id instanceof Integer) return (Integer)id;
        return -1;
    }

    private boolean mainDisplay(Object object) {
        Object unique = Reflect.read(object, "mUniqueDisplayId");
        if (unique instanceof String && !((String)unique).isEmpty()) {
            // An object's active unique ID takes priority over a historical identity map.
            return !primaryUniqueId.isEmpty() && primaryUniqueId.equals(unique);
        }
        // A live parent/current display ID wins over constructor-time history.
        if (object == null) return false;
        if (object.getClass().getName().equals("com.android.server.display.DisplayPowerControllerImpl")
                && Reflect.read(object,"mDisplayPowerController")==null) return false;
        int direct=directId(object);
        Integer remembered=displayIds.get(object);
        return (direct>=0?direct:remembered!=null?remembered:-1)==0;
    }

    private boolean allowed(Object object) {
        return "allow".equals(Policy.denyReason(enabled, target, sessionFault, mainDisplay(object),
                interactive, batteryAllowed, thermalStatus, sampledAt, SystemClock.elapsedRealtime()));
    }

    private void thermalEvent(String route, double request, double cap, boolean blocked, Object object) {
        attempts.incrementAndGet();
        if (mainDisplay(object)) { lastThermalAt = SystemClock.elapsedRealtime(); lastThermalCap = cap; }
        record(route, "request=" + request + ", heat_cap=" + cap + ", main=" + mainDisplay(object)
                + ", action=" + (blocked ? "bypass" : "observe") + ", gate="
                + Policy.denyReason(enabled, target, sessionFault, mainDisplay(object), interactive,
                    batteryAllowed, thermalStatus, sampledAt, SystemClock.elapsedRealtime()));
    }

    private void record(String kind, String text) {
        long now = SystemClock.elapsedRealtime();
        synchronized (events) {
            String key = kind + text;
            if (key.equals(lastEventKey) && now - lastEventAt < 30000) return;
            if (now - lastEventAt < 300 && !kind.equals("fault")) return;
            lastEventKey = key; lastEventAt = now;
            try {
                JSONObject e = new JSONObject().put("uptime_ms", now).put("kind", kind).put("detail", text);
                if (events.size() >= 48) events.removeFirst();
                events.addLast(e);
            } catch (Exception ignored) {}
        }
    }

    private void fault(String where, Throwable error) {
        if (faults.incrementAndGet() >= 3) sessionFault = true;
        record("fault", where + ": " + error);
        bridge.log("Module fault " + where + "; sessionFault=" + sessionFault + "; " + error);
    }

    private void sample() throws Exception {
        if (!"1".equals(systemProperty("sys.boot_completed"))) return;
        if (context == null) {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            Object c = Reflect.invoke(thread, "getSystemContext");
            if (c instanceof Context) context = (Context)c;
        }
        if (context == null) return;
        enabled = Settings.Global.getInt(context.getContentResolver(), Keys.ENABLED, 0) == 1;
        threshold = Policy.clampLimit(Settings.Global.getInt(context.getContentResolver(), Keys.LIMIT, 45));
        String reset = Settings.Global.getString(context.getContentResolver(), Keys.RESET);
        if (reset != null && !reset.equals(resetToken)) { resetToken = reset; faults.set(0); sessionFault = false; monitor.reset(); }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        batteryC = battery != null && battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE) / 10.0 : Double.NaN;
        batteryAllowed = policy.update(batteryC, threshold);
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        thermalStatus = pm == null ? -1 : pm.getCurrentThermalStatus();
        interactive = pm != null && pm.isInteractive();
        sampledAt = SystemClock.elapsedRealtime();
        lastGate = Policy.denyReason(enabled, target, sessionFault, true, interactive, batteryAllowed,
                thermalStatus, sampledAt, sampledAt);
        boolean allow = lastGate.equals("allow");
        if (allow != previousAllow) { previousAllow = allow; recalculate(); record("gate", lastGate); }
        output = Double.NaN;
        Object mainController=null;
        List<WeakReference<Object>> controllerSnapshot;
        synchronized (controllers) { controllerSnapshot = new ArrayList<>(controllers); }
            for (WeakReference<Object> ref : controllerSnapshot) {
                Object controller = ref.get();
                if (controller == null) continue;
                if (!mainDisplay(controller)) continue;
                mainController=controller;
                Object powerState = Reflect.read(controller, "mPowerState");
                double value = Reflect.number(powerState, "mScreenBrightness");
                if (Double.isFinite(value)) { output = value; break; }
            }
        int request = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        int mode = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        List<Object> mainObjects=new ArrayList<>();
        synchronized(displayIds) { for(Object o:displayIds.keySet()) if(mainDisplay(o)) mainObjects.add(o); }
        synchronized(modifiers) { for(WeakReference<Object> ref:modifiers) {
            Object o=ref.get(); if(o!=null && mainDisplay(o) && !mainObjects.contains(o)) mainObjects.add(o);
        } }
        try {
            brightnessReport=monitor.collect(mainController,primaryUniqueId,sampledAt,interactive,mode,mainObjects);
            if(brightnessReport.optBoolean("new_output_drop")) {
                drops.incrementAndGet(); record("brightness_output_drop",brightnessReport.optString("assessment")
                        + "; output="+output+"; brightness floats are not physical nit");
            }
        } catch(Throwable e) {
            brightnessReport=new JSONObject().put("assessment","data_unavailable").put("read_error",e.toString());
        }
        writeStatus(request, mode);
    }

    private static String systemProperty(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return (String)type.getMethod("get", String.class).invoke(null, name);
        } catch (Throwable ignored) { return ""; }
    }

    private void recalculate() {
        synchronized (modifiers) {
            for (WeakReference<Object> ref : modifiers) {
                Object object = ref.get();
                if (object == null || !mainDisplay(object)) continue;
                Object handler = Reflect.read(object, "mHandler"), listener = Reflect.read(object, "mChangeListener");
                if (handler instanceof Handler && listener != null) ((Handler)handler).post(() -> {
                    try { Reflect.invoke(listener, "onChanged"); } catch (Throwable e) { record("refresh_error", e.toString()); }
                });
            }
        }
        synchronized (controllers) {
            for (WeakReference<Object> ref : controllers) {
                Object object = ref.get();
                Object handler = Reflect.read(object, "mHandler");
                if (handler instanceof Handler) ((Handler)handler).post(() -> {
                    try { Reflect.invoke(object, "sendUpdatePowerState"); } catch (Throwable e) { record("refresh_error", e.toString()); }
                });
            }
        }
    }

    private void writeStatus(int request, int mode) throws Exception {
        JSONObject state = new JSONObject();
        state.put("version", Keys.VERSION).put("api", bridge.apiName()).put("device", Build.DEVICE)
                .put("firmware", Build.VERSION.INCREMENTAL).put("sdk", Build.VERSION.SDK_INT)
                .put("target_supported", target).put("enabled", enabled).put("gate", lastGate)
                .put("sampled_uptime_ms", sampledAt).put("battery_c", Double.isFinite(batteryC) ? batteryC : JSONObject.NULL)
                .put("temperature_limit_c", threshold).put("thermal_status", thermalStatus)
                .put("main_display_verified", !primaryUniqueId.isEmpty()).put("manual_request_setting", request)
                .put("brightness_mode", mode).put("framework_output_0_1", Double.isFinite(output) ? output : JSONObject.NULL)
                .put("thermal_calls", attempts.get()).put("bypass_calls", bypasses.get()).put("output_drops", drops.get())
                .put("session_fault", sessionFault).put("faults", faults.get());
        JSONArray hooks = new JSONArray(); int mitigation = 0;
        for (Map.Entry<String,Boolean> h : installed.entrySet()) { hooks.put(h.getKey()); if (h.getValue()) mitigation++; }
        state.put("mitigation_hooks", mitigation).put("installed_hooks", hooks).put("interface_inventory", inventory);
        state.put("brightness_monitor",brightnessReport);
        JSONArray identities = new JSONArray();
        synchronized (displayIds) {
            for (Map.Entry<Object,Integer> entry : displayIds.entrySet()) {
                if (identities.length() >= 32) break;
                Object o = entry.getKey();
                if (o != null) identities.put(new JSONObject().put("class", o.getClass().getName())
                        .put("historical_display_id", entry.getValue()).put("current_display_id", directId(o))
                        .put("verified_main", mainDisplay(o)));
            }
        }
        state.put("display_identity_inventory", identities);
        synchronized (events) { state.put("recent_events", new JSONArray(new ArrayList<>(events))); }
        String json = state.toString();
        if (json.length() > Keys.REPORT_LIMIT) {
            state.remove("interface_inventory"); state.put("inventory_omitted_for_size",true); json=state.toString();
        }
        if (json.length() > Keys.REPORT_LIMIT) return;
        // A runtime-only, package-scoped report avoids continuously persisting Settings XML.
        context.sendBroadcast(new Intent(Keys.REPORT_ACTION).setPackage(Keys.APP).putExtra("report", json));
    }
}
