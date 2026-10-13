package io.github.popsicle.brightnessguard;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One persistent su session while the UI is visible. Reads a fixed sysfs allowlist only. */
public final class RootBacklightReader implements AutoCloseable {
    public interface Listener { void update(JSONObject value); }
    static final String SCRIPT=
        "while IFS= read -r bg_probe; do\n"+
        " [ \"$bg_probe\" = poll ] || break\n"+
        " bg_count=0\n"+
        " for bg_path in /sys/class/backlight/*; do\n"+
        "  [ -d \"$bg_path\" ] || continue\n"+
        "  bg_count=$((bg_count + 1)); [ \"$bg_count\" -le 16 ] || break\n"+
        "  bg_request=?; bg_actual=?; bg_max=?; bg_power=?\n"+
        "  if [ -r \"$bg_path/brightness\" ]; then IFS= read -r bg_request < \"$bg_path/brightness\"; fi\n"+
        "  if [ -r \"$bg_path/actual_brightness\" ]; then IFS= read -r bg_actual < \"$bg_path/actual_brightness\"; fi\n"+
        "  if [ -r \"$bg_path/max_brightness\" ]; then IFS= read -r bg_max < \"$bg_path/max_brightness\"; fi\n"+
        "  if [ -r \"$bg_path/bl_power\" ]; then IFS= read -r bg_power < \"$bg_path/bl_power\"; fi\n"+
        "  printf 'NODE|%s|%s|%s|%s|%s\\n' \"$bg_path\" \"$bg_request\" \"$bg_actual\" \"$bg_max\" \"$bg_power\"\n"+
        " done\n"+
        " bg_path=/sys/class/thermal/thermal_message/max_brightness\n"+
        " if [ -r \"$bg_path\" ]; then IFS= read -r bg_cap < \"$bg_path\"; printf 'CAP|%s|%s\\n' \"$bg_path\" \"$bg_cap\"; fi\n"+
        " printf '__BG_END__\\n'\n"+
        "done\n";
    private final Listener listener;
    private volatile boolean closed;
    private volatile Process process;
    private volatile long lastProgress;
    private Thread thread;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,double[]> previous=new HashMap<>();
    public RootBacklightReader(Listener listener) { this.listener=listener; }
    public void start() {
        thread=new Thread(this::run,"BrightnessBacklightRead"); thread.setDaemon(true);
        lastProgress=SystemClock.elapsedRealtime();
        watchdog.scheduleAtFixedRate(() -> {
            if(!closed && SystemClock.elapsedRealtime()-lastProgress>30000) {
                sendError("Root 授权或节点读取超时，点击按钮重新连接"); close();
            }
        },5,5,TimeUnit.SECONDS);
        thread.start();
    }
    private static double parse(String value) {
        try { double v=Double.parseDouble(value.trim()); return Double.isFinite(v) && v>=0?v:Double.NaN; }
        catch(Throwable ignored) { return Double.NaN; }
    }
    public static JSONObject parseNode(String line) throws Exception {
        String[] parts=line.split("\\|",-1);
        if(parts.length!=6 || !parts[0].equals("NODE") || !parts[1].matches("/sys/class/backlight/[A-Za-z0-9_.-]+")) return null;
        double request=parse(parts[2]), actual=parse(parts[3]), max=parse(parts[4]), power=parse(parts[5]);
        if(Double.isFinite(max) && (max<=0 || max>10000000)) max=Double.NaN;
        return new JSONObject().put("path",parts[1]).put("request_raw",BrightnessMonitor.jsonNumber(request))
                .put("actual_raw",BrightnessMonitor.jsonNumber(actual)).put("maximum_raw",BrightnessMonitor.jsonNumber(max))
                .put("power_raw",BrightnessMonitor.jsonNumber(power)).put("main_display_verified",false)
                .put("driver_reported_fraction",BrightnessMonitor.jsonNumber(Double.isFinite(actual) && actual<=max?actual/max:Double.NaN));
    }
    private void run() {
        try {
            Process p=new ProcessBuilder("su","-c",SCRIPT).redirectErrorStream(true).start(); process=p;
            if(closed) { p.destroy(); return; }
            try (BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));
                    OutputStreamWriter writer=new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8)) {
                while(!closed) {
                    long started=SystemClock.elapsedRealtime();
                    writer.write("poll\n"); writer.flush();
                    JSONArray nodes=new JSONArray(); JSONObject thermalNode=null; int lines=0;
                    String line; boolean ended=false;
                    while((line=reader.readLine())!=null) {
                        if(closed) return;
                        if(line.equals("__BG_END__")) { ended=true; break; }
                        if(++lines>80 || line.length()>1024) throw new Exception("节点输出格式异常");
                        JSONObject node=parseNode(line);
                        if(node!=null) {
                            String path=node.getString("path");
                            double actual=node.optDouble("actual_raw",Double.NaN), request=node.optDouble("request_raw",Double.NaN);
                            double[] old=previous.get(path);
                            double max=node.optDouble("maximum_raw",Double.NaN), power=node.optDouble("power_raw",Double.NaN);
                            boolean drop=old!=null && Double.isFinite(actual) && Double.isFinite(old[0])
                                    && actual<old[0] && Double.isFinite(max) && max==old[2]
                                    && (Double.isNaN(power)||power==0) && (Double.isNaN(old[3])||old[3]==0)
                                    && old[0]-actual>=Math.max(1,max*.01);
                            node.put("driver_value_dropped",drop).put("request_unchanged",old!=null && Double.isFinite(request) && request==old[1]);
                            previous.put(path,new double[]{actual,request,max,power}); nodes.put(node);
                        } else if(line.startsWith("CAP|/sys/class/thermal/thermal_message/max_brightness|")) {
                            String[] fields=line.split("\\|",-1);
                            if(fields.length==3) thermalNode=new JSONObject().put("path",fields[1]).put("value_raw",BrightnessMonitor.jsonNumber(parse(fields[2])));
                        }
                    }
                    if(!ended) throw new Exception("Root 读取会话已结束；检查 SukiSU 授权");
                    lastProgress=SystemClock.elapsedRealtime();
                    JSONObject result=new JSONObject().put("status",nodes.length()>0?"connected":"no_nodes")
                            .put("sampled_uptime_ms",lastProgress).put("nodes",nodes).put("values_are_nits",false)
                            .put("node_identity_note","节点名没有自动映射到主屏；actual_brightness 是驱动回报值，非光学测量");
                    if(thermalNode!=null) result.put("thermal_message_cap",thermalNode);
                    listener.update(result);
                    long rest=1000-(SystemClock.elapsedRealtime()-started);
                    if(rest>0) Thread.sleep(rest);
                }
            }
        } catch(Throwable e) { if(!closed) sendError(e.getMessage()==null?e.toString():e.getMessage()); }
        finally { close(); }
    }
    private void sendError(String message) {
        try { listener.update(new JSONObject().put("status","error").put("detail",message)); } catch(Throwable ignored) {}
    }
    public boolean isClosed() { return closed; }
    @Override public void close() {
        closed=true; watchdog.shutdownNow();
        Process p=process;
        if(p!=null) {
            try { p.getOutputStream().close(); } catch(Throwable ignored) {}
            p.destroy();
        }
        Thread t=thread; if(t!=null && t!=Thread.currentThread()) t.interrupt();
    }
}
