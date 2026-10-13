package io.github.popsicle.brightnessguard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler main = new Handler();
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private TextView connection, metrics, events, limitLabel, liveHeadline, liveValues, nodeValues;
    private Button enableButton, backlightButton;
    private BrightnessGraphView graph;
    private RootBacklightReader backlightReader;
    private JSONObject backlightReport=new JSONObject();
    private boolean readBacklight;
    private int backlightEpoch;
    private SeekBar limitBar;
    private boolean active, saving, receiverRegistered;
    private int selectedLimit = 45;
    private String currentReport = "";
    private String rawReport = "";
    private final BroadcastReceiver reportReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Keys.REPORT_ACTION.equals(intent.getAction())) return;
            String value = intent.getStringExtra("report");
            if (value == null || value.length() > Keys.REPORT_LIMIT) return;
            rawReport = value;
            updateStatus();
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); if (active) main.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(242, 245, 251));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(20), dp(22), dp(26));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            scroll.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        TextView eyebrow = text("POPSICLE  /  EXPERIMENTAL", 12, Color.rgb(47, 95, 192));
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(eyebrow);
        TextView title = text("亮度上限守护", 30, Color.rgb(22, 35, 60));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView intro = text("针对小米 17 Pro Max · 澎湃 OS 3\n检测温度造成的最高亮度上限，并尝试解除显示层限制。", 15, Color.rgb(78, 91, 112));
        intro.setPadding(0, dp(8), 0, dp(20));
        page.addView(intro);

        LinearLayout statusCard = card(page, "运行状态");
        connection = text("读取模块状态…", 16, Color.rgb(24, 43, 80));
        statusCard.addView(connection);
        metrics = text("", 14, Color.rgb(83, 97, 119));
        metrics.setPadding(0, dp(10), 0, 0);
        statusCard.addView(metrics);

        LinearLayout live=card(page,"实时主屏亮度 · 每秒更新");
        liveHeadline=text("等待主屏读数…",17,Color.rgb(24,43,80));
        liveHeadline.setTypeface(Typeface.DEFAULT,Typeface.BOLD); live.addView(liveHeadline);
        liveValues=text("",14,Color.rgb(83,97,119)); liveValues.setTextIsSelectable(true);
        liveValues.setPadding(0,dp(10),0,0); live.addView(liveValues);
        graph=new BrightnessGraphView(this); live.addView(graph,new LinearLayout.LayoutParams(-1,dp(150)));
        live.addView(text("蓝：框架输出   绿：亮度请求   橙：当前最高上限",12,Color.rgb(107,119,140)));
        Button reset=button("清除曲线，重新建立亮度对照",false);
        reset.setOnClickListener(v -> saveConfiguration(null)); live.addView(reset);
        live.addView(text("先保持亮度稳定约 3 秒建立对照。自动亮度下降、应用亮度覆盖和亮度动画会单独标记；仅凭下降不认定温控。",12,Color.rgb(107,119,140)));

        LinearLayout driver=card(page,"驱动背光读数");
        driver.addView(text("可通过 Root 读取背光节点。仅在本界面可见时每秒采样，离开界面停止；主屏和背屏的节点身份暂不自动推断。",13,Color.rgb(83,97,119)));
        nodeValues=text("尚未开启",13,Color.rgb(70,86,112)); nodeValues.setTextIsSelectable(true);
        nodeValues.setPadding(0,dp(10),0,0); driver.addView(nodeValues);
        backlightButton=button("开启 Root 背光读取",false);
        backlightButton.setOnClickListener(v -> toggleBacklight()); driver.addView(backlightButton);

        LinearLayout control = card(page, "显示热限亮");
        control.addView(text("默认仅观察。启用后只在已识别的主屏热限亮接口生效；自动亮度曲线保持系统控制。", 14, Color.rgb(83, 97, 119)));
        enableButton = button("启用热限亮拦截", true);
        enableButton.setOnClickListener(v -> save(true));
        control.addView(enableButton);
        Button pause = button("关闭拦截，保留诊断", false);
        pause.setOnClickListener(v -> save(false));
        control.addView(pause);
        limitLabel = text("电池温度达到 45℃ 时恢复系统亮度保护", 14, Color.rgb(59, 77, 108));
        limitLabel.setPadding(0, dp(16), 0, 0);
        control.addView(limitLabel);
        limitBar = new SeekBar(this);
        limitBar.setMax(10); limitBar.setProgress(7);
        limitBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                selectedLimit = 38 + progress;
                limitLabel.setText("电池温度达到 " + selectedLimit + "℃ 时恢复系统亮度保护");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        control.addView(limitBar);
        control.addView(text("调整后点击启用保存。温度回落 2℃ 后才重新拦截；严重过热、读数未知或读数过期时沿用系统保护。", 12, Color.rgb(107, 119, 140)));

        LinearLayout logCard = card(page, "最近的亮度限制");
        events = text("尚无记录", 13, Color.rgb(70, 86, 112));
        events.setTextIsSelectable(true);
        logCard.addView(events);
        Button export = button("导出诊断报告", false);
        export.setOnClickListener(v -> {
            if (currentReport.isEmpty()) { toast("尚未连接系统框架，暂时没有报告"); return; }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("application/json");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "popsicle-brightness-" + System.currentTimeMillis() + ".json");
            startActivityForResult(intent, 10);
        });
        logCard.addView(export);
        TextView foot = text("" + Keys.VERSION + " · " + VariantInfo.API + "\n\n覆盖安装后重启加载新版钩子。实时监测在拦截暂停时仍然工作。0–1 和背光比例不是物理亮度百分比；估算 nit 来自系统映射，不能代替光学测量。驱动或面板内部仍可能限亮。\n\n所有记录留在本机。应用不联网。", 12, Color.rgb(101, 115, 137));
        foot.setPadding(dp(4), dp(8), dp(4), 0);
        page.addView(foot);
        selectedLimit = Policy.clampLimit(Settings.Global.getInt(getContentResolver(), Keys.LIMIT, 45));
        limitBar.setProgress(selectedLimit - 38);
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        IntentFilter filter = new IntentFilter(Keys.REPORT_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(reportReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(reportReceiver, filter);
        receiverRegistered = true;
        if(readBacklight) startBacklight();
        main.post(refresh);
    }
    @Override protected void onPause() {
        active = false; main.removeCallbacks(refresh);
        backlightEpoch++;
        if(backlightReader!=null) { backlightReader.close(); backlightReader=null; }
        if (receiverRegistered) { unregisterReceiver(reportReceiver); receiverRegistered = false; }
        super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); jobs.shutdownNow(); super.onDestroy(); }

    private void updateStatus() {
        try {
            String json = rawReport;
            if (json == null || json.isEmpty()) {
                currentReport = "";
                connection.setText("系统框架尚未连接");
                metrics.setText("请在 LSPosed 启用模块、勾选系统框架，然后重启。\n本机：" + Build.DEVICE + " / " + Build.VERSION.INCREMENTAL);
                liveHeadline.setText("系统框架尚未连接");
                showBacklight();
                return;
            }
            JSONObject state = new JSONObject(json);
            state.put("root_backlight_monitor",backlightReport);
            currentReport = state.toString(2);
            long age = SystemClock.elapsedRealtime() - state.optLong("sampled_uptime_ms", -1);
            boolean fresh = age >= 0 && age <= Policy.MAX_AGE_MS && Keys.VERSION.equals(state.optString("version"))
                    && VariantInfo.API.equals(state.optString("api"));
            boolean enabled = state.optBoolean("enabled");
            String gate = state.optString("gate", "unknown");
            String headline = !fresh ? "连接已过期，请检查模块或重启" : !state.optBoolean("target_supported") ? "机型或系统不匹配 · 仅观察"
                    : state.optInt("mitigation_hooks") == 0 ? "未匹配到可拦截接口 · 仅观察"
                    : enabled ? "allow".equals(gate) ? "拦截已启用 · 等待或处理热限亮" : "拦截暂停 · " + gateLabel(gate)
                    : "诊断模式 · 未修改亮度限制";
            connection.setText(headline);
            Object temp = state.opt("battery_c"), out = state.opt("framework_output_0_1");
            metrics.setText("电池温度  " + (temp == null || temp == JSONObject.NULL ? "未知" : temp + "℃")
                    + "    系统热状态  " + thermalLabel(state.optInt("thermal_status", -1))
                    + "\n已匹配拦截接口  " + state.optInt("mitigation_hooks")
                    + "    主屏身份  " + (state.optBoolean("main_display_verified") ? "已核验" : "待核验")
                    + "\n热限亮调用  " + state.optLong("thermal_calls") + "    拦截调用  " + state.optLong("bypass_calls")
                    + "\n框架输出（0–1）  " + (out == null || out == JSONObject.NULL ? "未知" : out)
                    + "\n" + state.optString("firmware") + " / " + state.optString("api"));
            showLive(state.optJSONObject("brightness_monitor"),fresh);
            showBacklight();
            JSONArray history = state.optJSONArray("recent_events");
            StringBuilder text = new StringBuilder();
            if (history != null) for (int i = history.length() - 1; i >= Math.max(0, history.length() - 6); i--) {
                JSONObject event = history.getJSONObject(i);
                text.append(event.optString("kind")).append("\n").append(event.optString("detail")).append("\n\n");
            }
            events.setText(text.length() == 0 ? "尚未观察到显示接口的热限亮调用。这里不会把普通亮度下降自动认定为温控。" : text.toString().trim());
            if (!saving) enableButton.setText(enabled ? "保存设置并继续拦截" : "启用热限亮拦截");
        } catch (Throwable e) { connection.setText("状态读取失败"); metrics.setText(e.toString()); }
    }

    private void save(boolean enable) { saveConfiguration(Boolean.valueOf(enable)); }
    private void saveConfiguration(Boolean enable) {
        if (saving) return;
        saving = true; enableButton.setEnabled(false);
        final int limit = selectedLimit;
        jobs.submit(() -> {
            String error = null;
            try {
                String command = (enable==null?"":"settings put global " + Keys.ENABLED + " " + (enable ? 1 : 0)
                        + " && settings put global " + Keys.LIMIT + " " + limit + " && ")
                        + "settings put global " + Keys.RESET + " " + System.currentTimeMillis();
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new Exception("Root 授权超时"); }
                ByteArrayOutputStream log = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int n;
                while ((n = process.getInputStream().read(buffer)) > 0 && log.size() < 8192) log.write(buffer, 0, n);
                if (process.exitValue() != 0) throw new Exception(new String(log.toByteArray(), StandardCharsets.UTF_8));
                int actual = Settings.Global.getInt(getContentResolver(), Keys.ENABLED, -1);
                if (enable!=null && actual != (enable ? 1 : 0)) throw new Exception("配置写入没有得到确认");
            } catch (Throwable e) { error = e.getMessage(); }
            final String message = error == null ? enable==null?"已请求清除曲线，重新建立对照":"设置已保存，模块连接后约 1 秒更新状态" : "保存失败：" + error;
            main.post(() -> { saving = false; enableButton.setEnabled(true); toast(message); updateStatus(); });
        });
    }

    private static String number(JSONObject object,String key,int decimals) {
        double value=object==null?Double.NaN:object.optDouble(key,Double.NaN);
        return Double.isFinite(value)?String.format(Locale.ROOT,"%."+decimals+"f",value):"未知";
    }
    private void showLive(JSONObject data,boolean fresh) {
        if(data==null || !fresh) {
            liveHeadline.setText(fresh?"新版系统监测尚未提供读数":"读数过期 · 等待重新连接");
            liveValues.setText("覆盖安装后需重启，才能加载 0.2.0 的系统服务钩子。"); return;
        }
        String code=data.optString("assessment","data_unavailable");
        liveHeadline.setText(assessmentLabel(code));
        liveHeadline.setTextColor(code.contains("thermal")?0xffb56716:code.contains("drop")?0xffb84c39:0xff182b50);
        String text="当前模式  "+(data.optInt("brightness_mode",-1)==0?"手动":data.optInt("brightness_mode",-1)==1?"自动":"未知")
                +"\n亮度请求（0–1）  "+number(data,"request_0_1",4)
                +"\n框架当前输出（0–1）  "+number(data,"framework_output_0_1",4)
                +"\n与上秒相比  "+deltaLabel(data,"output_delta_0_1")
                +"\n系统调整后的目标（0–1）  "+number(data,"info_adjusted_0_1",4)
                +"\n当前最高上限（0–1）  "+number(data,"maximum_0_1",4)
                +"    原因："+maxReasonLabel(data.optInt("maximum_reason",-1))
                +"\n最高上限变化  "+deltaLabel(data,"maximum_delta_0_1")
                +"\nAOSP 内部热上限（0–1）  "+number(data,"stored_thermal_cap_0_1",4)
                +"\n输出下降记录  "+data.optLong("output_drop_count")+" 次";
        if(Double.isFinite(data.optDouble("mapped_estimate_nits",Double.NaN)))
            text+="\n系统映射估算  "+number(data,"mapped_estimate_nits",1)+" nit（非实测）"
                    +"\n当前上限映射估算  "+number(data,"mapped_maximum_nits",1)+" nit";
        else text+="\n估算 nit  无可用系统映射";
        JSONArray vendor=data.optJSONArray("vendor_display_values");
        if(vendor!=null) for(int i=0;i<vendor.length();i++) {
            JSONObject item=vendor.optJSONObject(i), values=item==null?null:item.optJSONObject("values_raw_units");
            if(values!=null && values.has("mCurrentMaxThermalBrightness"))
                text+="\n小米热上限（厂商原始单位）  "+number(values,"mCurrentMaxThermalBrightness",4);
            if(values!=null && values.has("mBatteryThermalLimitBrightness"))
                text+="\n小米电池热上限（厂商原始单位）  "+number(values,"mBatteryThermalLimitBrightness",4);
        }
        liveValues.setText(text); graph.setSamples(data.optJSONArray("history"));
    }
    private static String assessmentLabel(String code) {
        switch(code) {
            case "system_reports_thermal_cap": return "系统明确报告：最高亮度受热限制";
            case "thermal_request_reduced": return "最近热调整调用降低了亮度请求";
            case "thermal_request_bypassed": return "最近热调整已被绕过 · 面板效果待确认";
            case "thermal_cap_stored": return "检测到内部热上限 · 是否应用待确认";
            case "manual_output_drop": return "请求未变，主屏框架输出下降";
            case "output_drop_unknown_cause": return "主屏框架输出下降 · 原因待确认";
            case "maximum_lowered_unknown_reason": return "最近最高上限下降 · 原因待确认";
            case "vendor_thermal_cap_value_decreased": return "小米热上限数值下降 · 应用情况待确认";
            case "request_changed": return "亮度请求发生变化 · 重新建立对照";
            case "window_override": return "应用窗口正在覆盖亮度";
            case "brightness_transition": return "亮度过渡中 · 暂不判断异常下降";
            case "screen_off": return "主屏未点亮 · 暂停下降判断";
            case "collecting_baseline": return "正在建立亮度对照";
            case "no_recent_drop_detected": return "最近未检测到输出下降";
            default: return "数据不足，无法判断是否降亮";
        }
    }
    private static String maxReasonLabel(int value) {
        switch(value) { case 0:return "未报告热限制"; case 1:return "温控"; case 2:return "功率限制"; case 3:return "睡眠模式"; default:return "未知"; }
    }
    private static String deltaLabel(JSONObject data,String key) {
        double value=data.optDouble(key,Double.NaN);
        if(!Double.isFinite(value)) return "无可比样本";
        if(Math.abs(value)<=.0001) return "无明显变化";
        return (value<0?"下降 ":"上升 ")+String.format(Locale.ROOT,"%.4f",Math.abs(value));
    }
    private void toggleBacklight() {
        if(readBacklight && backlightReader!=null && !backlightReader.isClosed()) {
            readBacklight=false; backlightReader.close(); backlightReader=null;
            backlightEpoch++;
            backlightReport=new JSONObject(); showBacklight();
        } else { readBacklight=true; startBacklight(); }
    }
    private void startBacklight() {
        if(!active || backlightReader!=null && !backlightReader.isClosed()) return;
        try {
            backlightReport=new JSONObject().put("status","authorizing"); showBacklight();
            final int epoch=++backlightEpoch;
            RootBacklightReader reader=new RootBacklightReader(value -> main.post(() -> {
                if(!active || !readBacklight || epoch!=backlightEpoch) return;
                backlightReport=value; showBacklight(); updateStatus();
            }));
            backlightReader=reader; reader.start();
        } catch(Throwable e) { nodeValues.setText("启动失败："+e.getMessage()); }
    }
    private void showBacklight() {
        String status=backlightReport.optString("status","");
        String text;
        if(!readBacklight) text="尚未开启";
        else if(status.equals("authorizing")) text="等待 SukiSU 授权并读取节点…";
        else if(status.equals("error")) text="读取失败："+backlightReport.optString("detail");
        else if(status.equals("no_nodes")) text="未发现可读取的背光节点。框架监测仍然有效。";
        else {
            long age=SystemClock.elapsedRealtime()-backlightReport.optLong("sampled_uptime_ms",-1);
            text=age<0 || age>3500?"背光读数过期\n":"驱动回报值（不是 nit；节点主屏身份未核验）\n";
            JSONArray nodes=backlightReport.optJSONArray("nodes");
            if(nodes!=null) for(int i=0;i<nodes.length();i++) {
                JSONObject n=nodes.optJSONObject(i); if(n==null) continue;
                text+="\n"+n.optString("path").replace("/sys/class/backlight/","")
                        +"\n请求 "+number(n,"request_raw",0)+" / 驱动回报 "+number(n,"actual_raw",0)+" / 最大刻度 "+number(n,"maximum_raw",0)
                        +"\n驱动刻度比例 "+number(n,"driver_reported_fraction",4)
                        +(n.optBoolean("driver_value_dropped")?"\n本次驱动回报值下降"+(n.optBoolean("request_unchanged")?"，节点请求未变":""):"")+"\n";
            }
            JSONObject cap=backlightReport.optJSONObject("thermal_message_cap");
            if(cap!=null) text+="\nthermal_message 上限节点  "+number(cap,"value_raw",0)+"（原始单位）";
        }
        nodeValues.setText(text.trim());
        boolean running=readBacklight && backlightReader!=null && !backlightReader.isClosed();
        backlightButton.setText(running?"停止 Root 背光读取":"开启 / 重新连接 Root 背光读取");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null) return;
        Uri uri = data.getData(); String report = currentReport;
        jobs.submit(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("无法打开文件");
                out.write(report.getBytes(StandardCharsets.UTF_8));
                main.post(() -> toast("报告已保存"));
            } catch (Throwable e) { main.post(() -> toast("导出失败：" + e.getMessage())); }
        });
    }

    private String gateLabel(String gate) {
        if (gate.equals("battery_temperature_guard")) return "电池温度保护";
        if (gate.equals("system_thermal_guard")) return "系统严重过热或热状态未知";
        if (gate.equals("screen_not_interactive")) return "屏幕未唤醒";
        if (gate.equals("session_fault")) return "检测到模块异常";
        if (gate.equals("stale_sample")) return "读数过期";
        return gate;
    }
    private String thermalLabel(int status) {
        String[] labels = {"正常", "轻微", "中等", "严重", "临界", "紧急", "关机"};
        return status >= 0 && status < labels.length ? labels[status] : "未知";
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v;
    }
    private LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(20)); card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); page.addView(card, params);
        TextView label = text(title, 17, Color.rgb(27, 44, 73)); label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, 0, 0, dp(12)); card.addView(label); return card;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(49, 88, 161));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12)); bg.setColor(primary ? Color.rgb(49, 95, 198) : Color.rgb(236, 242, 254));
        b.setBackground(bg); b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(12); b.setLayoutParams(p); return b;
    }
}
