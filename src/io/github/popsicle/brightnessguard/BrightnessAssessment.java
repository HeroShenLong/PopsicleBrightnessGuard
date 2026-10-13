package io.github.popsicle.brightnessguard;

/** Evidence-based, Android-independent assessment. Brightness floats are not physical nit. */
public final class BrightnessAssessment {
    public static final class Sample {
        public long time;
        public String display = "";
        public boolean awake, animating, windowOverride, explicitThermalReduction, bypassed;
        public int mode = -1, maxReason = -1;
        public double request = Double.NaN, output = Double.NaN, maximum = Double.NaN, thermalCap = Double.NaN;
    }
    public static final class Result {
        public final String code;
        public final boolean newDrop;
        public final double baseline, drop;
        Result(String code, boolean newDrop, double baseline, double drop) {
            this.code=code; this.newDrop=newDrop; this.baseline=baseline; this.drop=drop;
        }
    }
    private Sample previous;
    private double peak=Double.NaN;
    private int stable;
    private long lastDropAt=-1;
    private double lastDrop=Double.NaN, lastBaseline=Double.NaN;
    private String lastDropCode="";

    public void reset() { previous=null; peak=Double.NaN; stable=0; lastDropAt=-1; }
    public static boolean valid(double value) { return Double.isFinite(value) && value>=0 && value<=1; }
    public static boolean significant(double from, double to) {
        return valid(from) && valid(to) && from-to>=.01 && from-to>=from*.12;
    }
    public Result assess(Sample s) {
        if (!s.awake || !valid(s.output) || s.display.isEmpty()) {
            reset(); return new Result(s.awake?"data_unavailable":"screen_off",false,Double.NaN,Double.NaN);
        }
        boolean continuity=previous!=null && previous.awake && previous.display.equals(s.display)
                && s.time>previous.time && s.time-previous.time<=3500 && s.mode==previous.mode;
        boolean requestSame=continuity && valid(s.request) && valid(previous.request)
                && Math.abs(s.request-previous.request)<=.003;
        boolean requestChanged=s.mode==0 && continuity && valid(s.request) && valid(previous.request) && !requestSame;
        boolean resetBaseline=!continuity || requestChanged || s.windowOverride || s.animating;
        if (resetBaseline) { peak=s.output; stable=0; lastDropAt=-1; }
        boolean drop=false;
        String dropCode=s.mode==0 && requestSame?"manual_output_drop":"output_drop_unknown_cause";
        if (!resetBaseline) {
            if (stable>=2 && significant(peak,s.output)) {
                drop=true; lastBaseline=peak; lastDrop=peak-s.output; lastDropAt=s.time; lastDropCode=dropCode;
                peak=s.output; stable=0;
            } else { peak=Double.isNaN(peak)?s.output:Math.max(peak,s.output); stable++; }
        }
        previous=s;
        String code;
        if (s.maxReason==1 && Policy.normalizedCap(s.maximum)) code="system_reports_thermal_cap";
        else if (s.explicitThermalReduction) code=s.bypassed?"thermal_request_bypassed":"thermal_request_reduced";
        else if (Policy.normalizedCap(s.thermalCap)) code="thermal_cap_stored";
        else if (requestChanged) code="request_changed";
        else if (s.windowOverride) code="window_override";
        else if (s.animating) code="brightness_transition";
        else if (lastDropAt>=0 && s.time-lastDropAt<=10000) code=lastDropCode;
        else code=stable>=2?"no_recent_drop_detected":"collecting_baseline";
        return new Result(code,drop,drop||(lastDropAt>=0 && s.time-lastDropAt<=10000)?lastBaseline:peak,
                lastDropAt>=0 && s.time-lastDropAt<=10000?lastDrop:Double.NaN);
    }
}
