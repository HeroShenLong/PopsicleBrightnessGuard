import io.github.popsicle.brightnessguard.Policy;
import io.github.popsicle.brightnessguard.Reflect;

public final class PolicyTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String gate(boolean on, boolean target, boolean fault, boolean main, boolean awake,
            boolean battery, int thermal, long sampled, long now) {
        return Policy.denyReason(on,target,fault,main,awake,battery,thermal,sampled,now);
    }
    static class Parent { private int mDisplayId = 0; }
    static class Child extends Parent { private float mBrightnessCap = .6f; }
    public static void main(String[] args) {
        check(Policy.targetDevice("popsicle","OS3.0.319.0.WPBCNXM",36),"target OS3 Android16");
        check(Policy.targetDevice("popsicle","OS3.0.3.0.WPBCNXM",35),"target Android15");
        check(!Policy.targetDevice("pudding","OS3.0.319",36),"other Xiaomi blocked");
        check(!Policy.targetDevice("popsicle","OS4.0.0",36),"OS4 blocked");
        check(!Policy.targetDevice("popsicle","OS3.0.319",37),"future Android blocked");
        check(!Policy.targetDevice("popsicle",null,36),"unknown firmware blocked");
        Policy p = new Policy();
        check(p.update(44,45),"below threshold");
        check(!p.update(45,45),"at threshold");
        check(!p.update(44,45),"hysteresis holds");
        check(p.update(43,45),"hysteresis recovers");
        check(!p.update(Double.NaN,45),"unknown battery blocks");
        check(!p.update(44,45),"unknown then warm must cool");
        check(p.update(42,45),"unknown then cool recovers");
        check(!p.update(100,45),"invalid battery blocks");
        check(Policy.clampLimit(200)==48 && Policy.clampLimit(-1)==38,"limits bounded");
        check(gate(true,true,false,true,true,true,2,1000,2000).equals("allow"),"recognized ordinary heat");
        check(!gate(false,true,false,true,true,true,0,1000,2000).equals("allow"),"default observation");
        check(!gate(true,false,false,true,true,true,0,1000,2000).equals("allow"),"wrong model");
        check(!gate(true,true,true,true,true,true,0,1000,2000).equals("allow"),"session error");
        check(!gate(true,true,false,false,true,true,0,1000,2000).equals("allow"),"rear or unknown display");
        check(!gate(true,true,false,true,false,true,0,1000,2000).equals("allow"),"screen off");
        check(!gate(true,true,false,true,true,false,0,1000,2000).equals("allow"),"hot battery");
        for(int status:new int[]{-1,3,4,5,6}) check(!gate(true,true,false,true,true,true,status,1000,2000).equals("allow"),"severe/unknown status");
        check(!gate(true,true,false,true,true,true,0,1000,13001).equals("allow"),"stale sample");
        check(!gate(true,true,false,true,true,true,0,3000,2000).equals("allow"),"stale report after reboot");
        check(!gate(true,true,false,true,true,true,0,0,2000).equals("allow"),"uninitialized sample");
        check(Policy.normalizedCap(.6),"300/500 type cap");
        for(double cap:new double[]{-1,1,500,Double.NaN,Double.POSITIVE_INFINITY}) check(!Policy.normalizedCap(cap),"invalid or uncapped cap");
        check(Policy.thermalAdjustment(500,300),"preserve requested brightness at explicit thermal adjust");
        check(Policy.thermalAdjustment(.8,.6),"normalized thermal adjust");
        for(double[] pair:new double[][]{{300,500},{500,500},{500,-1},{20000,300},{Double.NaN,300},{500,Double.POSITIVE_INFINITY}}) check(!Policy.thermalAdjustment(pair[0],pair[1]),"invalid or no heat reduction");
        Child child = new Child();
        check(Reflect.number(child,"mDisplayId")==0,"inherited display field");
        check(Math.abs(Reflect.number(child,"mBrightnessCap")-.6)<.00001,"private cap field");
        check(Double.isNaN(Reflect.number(child,"unknown")),"unknown firmware field");
        check(Reflect.read(null,"mDisplayId")==null,"null receiver");
        System.out.println("PASS " + checks + " policy/reflection checks");
    }
}
