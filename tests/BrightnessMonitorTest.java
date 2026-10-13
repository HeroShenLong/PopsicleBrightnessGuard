import io.github.popsicle.brightnessguard.*;
import java.util.Arrays;
import org.json.JSONObject;

public final class BrightnessMonitorTest {
    static int checks;
    static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    static BrightnessAssessment.Sample s(long t,int mode,double request,double output) {
        BrightnessAssessment.Sample s=new BrightnessAssessment.Sample();
        s.time=t; s.display="main"; s.awake=true; s.mode=mode; s.request=request; s.output=output; return s;
    }
    static void settle(BrightnessAssessment d,int mode) { for(int i=1;i<=4;i++) d.assess(s(i*1000,mode,.9,.8)); }
    public static final class Power {
        private float mScreenBrightness=.5f,mSdrScreenBrightness=.4f; private int mScreenState=2;
    }
    public static final class Info {
        public float brightness=.9f,adjustedBrightness=.5f,brightnessMaximum=.6f;
        public int brightnessMaxReason=1; public boolean isBrightnessOverrideByWindow;
    }
    public static final class Mapper { public float convertToNits(float brightness) { return brightness*1000; } }
    public static final class Controller {
        private Power mPowerState=new Power(); private Mapper mDisplayBrightnessController=new Mapper();
        private Info info=new Info();
        public Info getBrightnessInfo() { return info; }
        public float getScreenBrightnessSetting() { return .9f; }
    }
    public static final class ThermalBrightnessController {
        private float mCurrentMaxThermalBrightness=350;
    }
    public static void main(String[] args) throws Exception {
        BrightnessAssessment d=new BrightnessAssessment();
        check(d.assess(s(1000,0,.9,.8)).code.equals("collecting_baseline"),"no baseline is not proof of normal output");
        d.reset(); settle(d,0); BrightnessAssessment.Result r=d.assess(s(5000,0,.9,.5));
        check(r.code.equals("manual_output_drop") && r.newDrop,"manual unchanged request with lower output");
        check(Math.abs(r.drop-.3)<.00001,"measured logical drop");
        check(!d.assess(s(6000,0,.9,.5)).newDrop,"same drop not repeatedly counted");
        d.reset(); settle(d,0); r=d.assess(s(5000,0,.5,.4));
        check(r.code.equals("request_changed") && !r.newDrop,"slider change starts new baseline");
        d.reset(); settle(d,1); r=d.assess(s(5000,1,.5,.4));
        check(r.code.equals("output_drop_unknown_cause") && r.newDrop,"auto drop never labeled thermal without evidence");
        d.reset(); settle(d,0); BrightnessAssessment.Sample input=s(5000,0,.9,.4); input.maximum=.6; input.maxReason=1;
        check(d.assess(input).code.equals("system_reports_thermal_cap"),"reason=thermal and valid maximum is direct cap evidence");
        input=s(6000,0,.9,.4); input.thermalCap=.6;
        check(d.assess(input).code.equals("thermal_cap_stored"),"stored cap does not prove applied cap");
        input=s(7000,0,.9,.4); input.explicitThermalReduction=true; input.bypassed=true;
        check(d.assess(input).code.equals("thermal_request_bypassed"),"bypass reported separately from physical success");
        input=s(8000,0,.9,.4); input.explicitThermalReduction=true;
        check(d.assess(input).code.equals("thermal_request_reduced"),"observed Xiaomi method reduction");
        d.reset(); settle(d,0); input=s(5000,0,.9,.4); input.animating=true;
        check(!d.assess(input).newDrop,"transition excluded from abnormal drop assessment");
        d.reset(); settle(d,0); input=s(5000,0,.9,0); input.awake=false;
        check(d.assess(input).code.equals("screen_off"),"screen off excluded");
        check(!d.assess(s(6000,0,.9,.2)).newDrop,"wakeup does not compare with pre-sleep output");
        d.reset(); settle(d,0); check(!d.assess(s(10000,0,.9,.2)).newDrop,"long sample gap excluded");
        input=s(11000,0,.9,.2); input.display="rear";
        check(!d.assess(input).newDrop,"display change excluded");
        input=s(12000,0,.9,Double.NaN);
        check(d.assess(input).code.equals("data_unavailable"),"unknown output excluded");
        d.reset(); settle(d,0); input=s(5000,0,.9,.4); input.windowOverride=true;
        check(d.assess(input).code.equals("window_override"),"window brightness override explained");
        check(!BrightnessAssessment.significant(.8,.795),"small numerical noise excluded");
        check(!BrightnessAssessment.significant(500,300),"vendor raw unit cannot be treated as normalized float");
        BrightnessMonitor monitor=new BrightnessMonitor();
        JSONObject report=monitor.collect(new Controller(),"main",1000,true,0,Arrays.asList(new ThermalBrightnessController()));
        check(report.optString("assessment").equals("system_reports_thermal_cap"),"collector reads system maximum reason");
        check(Math.abs(report.optDouble("framework_output_0_1",0)-.5)<.00001,"collector reads current power-state output");
        check(Math.abs(report.optDouble("mapped_estimate_nits",0)-400)<.01,"SDR output uses device mapping, not arbitrary percent conversion");
        check(!report.optBoolean("nits_are_photometer_measurement"),"mapping clearly marked non-physical measurement");
        Controller changed=new Controller(); BrightnessMonitor continuous=new BrightnessMonitor();
        continuous.collect(changed,"main",1000,true,0,Arrays.asList());
        changed.mPowerState.mScreenBrightness=.4f;
        report=continuous.collect(changed,"main",2000,true,0,Arrays.asList());
        check(report.optBoolean("output_decreased_since_previous_sample"),"actual numeric decrease recorded independently of baseline/animation classification");
        changed.info.brightnessMaxReason=0; changed.info.brightnessMaximum=.4f;
        report=continuous.collect(changed,"main",3000,true,0,Arrays.asList());
        check(report.optBoolean("maximum_decreased_since_previous_sample") && report.optString("assessment").equals("maximum_lowered_unknown_reason"),"cap reduction recognized even when current output unchanged");
        check(!report.optBoolean("output_decreased_since_previous_sample"),"cap reduction not fabricated as output reduction");
        ThermalBrightnessController vendor=new ThermalBrightnessController();
        BrightnessMonitor vendorMonitor=new BrightnessMonitor(); Controller vendorController=new Controller();
        vendorController.info.brightnessMaxReason=0;
        vendorMonitor.collect(vendorController,"main",1000,true,0,Arrays.asList(vendor));
        vendor.mCurrentMaxThermalBrightness=250;
        report=vendorMonitor.collect(vendorController,"main",2000,true,0,Arrays.asList(vendor));
        check(report.optBoolean("vendor_thermal_cap_value_decreased"),"known Xiaomi cap field decrease read even when callbacks never fire");
        check(report.optString("assessment").equals("vendor_thermal_cap_value_decreased"),"vendor raw cap change is not called confirmed applied cap");
        report=continuous.collect(changed,"main",10000,true,0,Arrays.asList());
        check(Double.isNaN(report.optDouble("output_delta_0_1",Double.NaN)),"missing interval has no fake delta");
        check(Double.isNaN(report.optDouble("stored_thermal_cap_0_1",Double.NaN)),"350-unit vendor cap not treated as normalized cap");
        for(int i=2;i<=110;i++) report=monitor.collect(new Controller(),"main",i*1000,true,0,Arrays.asList(new ThermalBrightnessController()));
        check(report.optJSONArray("history").length()==90,"history is bounded");
        monitor.reset(); monitor.capture(110001,.85,.5,false,"xiaomi_thermal_input");
        report=monitor.collect(null,"main",111000,true,1,Arrays.asList());
        check(Math.abs(report.optDouble("request_0_1",0)-.85)<.00001,"auto mode uses fresh pre-thermal request");
        report=monitor.collect(null,"main",120000,true,1,Arrays.asList());
        check(Double.isNaN(report.optDouble("request_0_1",Double.NaN)),"old request is not reused as current");
        JSONObject node=RootBacklightReader.parseNode("NODE|/sys/class/backlight/panel0-backlight|2000|1000|4000|0");
        check(node!=null && node.optDouble("driver_reported_fraction",0)==.25,"driver fraction uses provided scale");
        check(!node.optBoolean("main_display_verified"),"node name does not imply verified main display");
        node=RootBacklightReader.parseNode("NODE|/sys/class/backlight/panel1-backlight|?|?|0|?");
        check(Double.isNaN(node.optDouble("driver_reported_fraction",Double.NaN)),"zero/missing scale does not create false actual reading");
        check(RootBacklightReader.parseNode("NODE|/data/local/tmp/panel|1|1|1|0")==null,"node read allowlist checked");
        check(RootBacklightReader.parseNode("NODE|/sys/class/backlight/panel|1|1")==null,"malformed node protocol rejected");
        System.out.println("PASS "+checks+" brightness monitoring checks (not device tests)");
    }
}
