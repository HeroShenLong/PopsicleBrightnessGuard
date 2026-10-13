package io.github.popsicle.brightnessguard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashMap;

/** Passive reads only. Independent of whether the thermal bypass is enabled. */
public final class BrightnessMonitor {
    private final BrightnessAssessment assessment=new BrightnessAssessment();
    private final ArrayDeque<JSONObject> history=new ArrayDeque<>();
    private volatile Request latestRequest;
    private volatile Request thermalRequest;
    private long dropCount;
    private long previousAt, maximumDropAt=-1, maximumDrops;
    private double previousOutput=Double.NaN,previousMaximum=Double.NaN;
    private String previousDisplay="";
    private boolean previousAwake;
    private final Map<Object,Map<String,Double>> vendorPrevious=new WeakHashMap<>();
    public static final class Request {
        final long time; final double request, limited; final boolean bypassed; final String source;
        Request(long time,double request,double limited,boolean bypassed,String source) {
            this.time=time; this.request=request; this.limited=limited; this.bypassed=bypassed; this.source=source;
        }
    }
    public void capture(long time,double request,double limited,boolean bypassed,String source) {
        if (!BrightnessAssessment.valid(request)) return;
        Request r=new Request(time,request,limited,bypassed,source);
        latestRequest=r;
        if (BrightnessAssessment.valid(limited) && limited<request-.0001) thermalRequest=r;
    }
    public void reset() {
        assessment.reset(); history.clear(); latestRequest=null; thermalRequest=null; dropCount=0;
        previousAt=0; maximumDropAt=-1; maximumDrops=0; previousAwake=false; previousDisplay="";
        previousOutput=Double.NaN; previousMaximum=Double.NaN;
        vendorPrevious.clear();
    }
    private static Object invoke(Object target,String name) {
        try { return Reflect.invoke(target,name); } catch(Throwable ignored) { return null; }
    }
    private static double numeric(Object v) { return v instanceof Number?((Number)v).doubleValue():Double.NaN; }
    private static double estimateNits(Object controller,double value) {
        if (!BrightnessAssessment.valid(value)) return Double.NaN;
        Object converter=Reflect.read(controller,"mDisplayBrightnessController");
        if (converter==null) return Double.NaN;
        try {
            Method m=converter.getClass().getMethod("convertToNits",float.class); m.setAccessible(true);
            double n=numeric(m.invoke(converter,(float)value));
            return Double.isFinite(n) && n>=0 && n<=10000?n:Double.NaN;
        } catch(Throwable ignored) { return Double.NaN; }
    }
    public static Object jsonNumber(double value) { return Double.isFinite(value)?value:JSONObject.NULL; }
    public JSONObject collect(Object controller,String display,long now,boolean interactive,int mode,
            List<Object> mainObjects) throws Exception {
        Object power=Reflect.read(controller,"mPowerState");
        double output=Reflect.number(power,"mScreenBrightness");
        double sdr=Reflect.number(power,"mSdrScreenBrightness");
        double screen=Reflect.number(power,"mScreenState");
        boolean awake=interactive && (Double.isNaN(screen)||screen==2);
        Object info=invoke(controller,"getBrightnessInfo");
        double logical=Reflect.number(info,"brightness"), adjusted=Reflect.number(info,"adjustedBrightness");
        double maximum=Reflect.number(info,"brightnessMaximum");
        double reason=Reflect.number(info,"brightnessMaxReason");
        double user=numeric(invoke(controller,"getScreenBrightnessSetting"));
        Request demand=latestRequest;
        boolean requestFresh=demand!=null && now>=demand.time && now-demand.time<=3500;
        double request=mode==0 && BrightnessAssessment.valid(user)?user:requestFresh?demand.request:Double.NaN;
        Request thermal=thermalRequest;
        boolean heatFresh=thermal!=null && now>=thermal.time && now-thermal.time<=3500;
        JSONArray caps=new JSONArray(), vendor=new JSONArray();
        double storedThermalCap=Double.NaN;
        boolean vendorDown=false;
        for (Object o:mainObjects) {
            String type=o.getClass().getName();
            if (type.endsWith("BrightnessThermalModifier") || type.endsWith("BrightnessThrottler")) {
                double cap=Reflect.number(o,"mBrightnessCap");
                caps.put(new JSONObject().put("source",type+".mBrightnessCap")
                        .put("value_0_1",jsonNumber(BrightnessAssessment.valid(cap)?cap:Double.NaN)));
                if (Policy.normalizedCap(cap)) storedThermalCap=Double.isNaN(storedThermalCap)?cap:Math.min(cap,storedThermalCap);
            }
            if (type.endsWith("ThermalBrightnessController") || type.endsWith("DisplayPowerControllerImpl")) {
                JSONObject fields=new JSONObject(); JSONArray reduced=new JSONArray(); int count=0;
                Map<String,Double> prior=vendorPrevious.get(o);
                Map<String,Double> next=new HashMap<>();
                for (String name:new String[]{"mCurrentMaxThermalBrightness","mThermalSafetyBrightness","mCurrentActualThermalConditionId",
                        "mCurrentAppliedThermalConditionId","mBatteryThermalLevel","mBatteryThermalLimitBrightness",
                        "mThermalMaxBrightness","mThermalBrightness","mActualScreenOnBrightness","mCurrentBrightness",
                        "mCurrentSdrBrightness","mDesiredBrightness","mBasedBrightness"}) {
                    double n=Reflect.number(o,name);
                    if (Double.isFinite(n)) {
                        fields.put(name,n); count++; next.put(name,n);
                        if(awake && previousAwake && now>previousAt && now-previousAt<=3500 && prior!=null
                                && (name.equals("mCurrentMaxThermalBrightness") || name.equals("mBatteryThermalLimitBrightness")
                                || name.equals("mThermalMaxBrightness") || name.equals("mThermalBrightness"))) {
                            Double old=prior.get(name);
                            if(old!=null && old>0 && n>=0 && old-n>Math.max(.0001,old*.001)) {
                                reduced.put(new JSONObject().put("field",name).put("from_raw",old).put("to_raw",n)); vendorDown=true;
                            }
                        }
                    }
                }
                vendorPrevious.put(o,next);
                if(count>0) vendor.put(new JSONObject().put("class",type).put("values_raw_units",fields).put("cap_value_decreases",reduced));
            }
        }
        BrightnessAssessment.Sample sample=new BrightnessAssessment.Sample();
        sample.time=now; sample.display=display; sample.awake=awake; sample.mode=mode;
        sample.request=request; sample.output=output; sample.maximum=maximum; sample.thermalCap=storedThermalCap;
        sample.maxReason=Double.isFinite(reason)?(int)reason:-1;
        sample.animating=Boolean.TRUE.equals(invoke(Reflect.read(controller,"mScreenBrightnessRampAnimator"),"isAnimating"));
        sample.windowOverride=Boolean.TRUE.equals(Reflect.read(info,"isBrightnessOverrideByWindow"));
        sample.explicitThermalReduction=heatFresh; sample.bypassed=heatFresh && thermal.bypassed;
        BrightnessAssessment.Result verdict=assessment.assess(sample);
        if (verdict.newDrop) dropCount++;
        boolean comparable=awake && previousAwake && display.equals(previousDisplay)
                && now>previousAt && now-previousAt<=3500;
        double delta=comparable && BrightnessAssessment.valid(output) && BrightnessAssessment.valid(previousOutput)?output-previousOutput:Double.NaN;
        double maxDelta=comparable && BrightnessAssessment.valid(maximum) && BrightnessAssessment.valid(previousMaximum)?maximum-previousMaximum:Double.NaN;
        boolean maxDown=Double.isFinite(maxDelta) && maxDelta<-.0001;
        if(maxDown) { maximumDropAt=now; maximumDrops++; }
        String code=verdict.code;
        if(vendorDown && (code.equals("no_recent_drop_detected") || code.equals("collecting_baseline"))) code="vendor_thermal_cap_value_decreased";
        if(maximumDropAt>=0 && now>=maximumDropAt && now-maximumDropAt<=3000
                && (code.equals("no_recent_drop_detected") || code.equals("collecting_baseline"))) code="maximum_lowered_unknown_reason";
        previousAt=now; previousAwake=awake; previousDisplay=display; previousOutput=output; previousMaximum=maximum;
        JSONObject current=new JSONObject().put("sampled_uptime_ms",now).put("sample_interval_ms",1000)
                .put("main_screen_awake",awake).put("brightness_mode",mode)
                .put("request_0_1",jsonNumber(request)).put("request_source",mode==0 && BrightnessAssessment.valid(user)?"screen_setting":requestFresh?demand.source:"unavailable")
                .put("framework_output_0_1",jsonNumber(output)).put("framework_sdr_output_0_1",jsonNumber(sdr))
                .put("info_brightness_0_1",jsonNumber(logical)).put("info_adjusted_0_1",jsonNumber(adjusted))
                .put("maximum_0_1",jsonNumber(maximum)).put("maximum_reason",sample.maxReason)
                .put("stored_thermal_cap_0_1",jsonNumber(storedThermalCap)).put("thermal_cap_sources",caps)
                .put("vendor_display_values",vendor).put("assessment",code)
                .put("output_delta_0_1",jsonNumber(delta)).put("output_decreased_since_previous_sample",Double.isFinite(delta) && delta<-.0001)
                .put("maximum_delta_0_1",jsonNumber(maxDelta)).put("maximum_decreased_since_previous_sample",maxDown)
                .put("maximum_decrease_count",maximumDrops)
                .put("vendor_thermal_cap_value_decreased",vendorDown)
                .put("new_output_drop",verdict.newDrop).put("output_drop_count",dropCount)
                .put("comparison_baseline_0_1",jsonNumber(verdict.baseline)).put("recent_drop_0_1",jsonNumber(verdict.drop))
                .put("brightness_animating",sample.animating).put("window_override",sample.windowOverride)
                .put("mapped_estimate_nits",jsonNumber(estimateNits(controller,BrightnessAssessment.valid(sdr)?sdr:output)))
                .put("mapped_maximum_nits",jsonNumber(estimateNits(controller,maximum)))
                .put("nits_are_photometer_measurement",false);
        if (heatFresh) current.put("recent_thermal_request",new JSONObject().put("request",thermal.request)
                .put("limited",thermal.limited).put("bypassed",thermal.bypassed).put("source",thermal.source));
        history.addLast(new JSONObject().put("uptime_ms",now).put("output",jsonNumber(output))
                .put("request",jsonNumber(request)).put("maximum",jsonNumber(maximum)).put("assessment",code));
        if(history.size()>90) history.removeFirst();
        current.put("history",new JSONArray(new ArrayList<>(history)));
        return current;
    }
}
