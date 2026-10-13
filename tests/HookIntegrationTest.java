import io.github.popsicle.brightnessguard.*;
import com.android.server.display.*;
import com.android.server.display.brightness.clamper.*;
import android.hardware.display.DisplayManagerInternal;
import java.lang.reflect.*;
import java.util.*;

/** Simulates callbacks against isolated display fixtures. This is not an ART or device test. */
public final class HookIntegrationTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void set(Object object, String name, Object value) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value);
    }
    static final class Bridge implements HookBridge {
        final Map<Executable,Callback> callbacks = new HashMap<>();
        public void hook(Executable e, Callback c) { callbacks.put(e,c); }
        public void deoptimize(Executable e) {}
        public void log(String message) {}
        public String apiName() { return "test-only"; }
        Object call(Executable method, Object receiver, Object... args) throws Throwable {
            HookBridge.Call call=new HookBridge.Call(receiver,args);
            Callback callback=callbacks.get(method);
            if (callback!=null) callback.before(call);
            if (!call.skip) try {
                if (method instanceof Method) call.result=((Method)method).invoke(receiver,args);
            } catch (InvocationTargetException e) { call.failure=e.getCause(); }
            call.changed=false;
            if (callback!=null) callback.after(call);
            if (call.failure!=null) throw call.failure;
            return call.result;
        }
        void constructed(Object object, Class<?>[] types, Object... args) throws Throwable {
            Constructor<?> ctor=object.getClass().getDeclaredConstructor(types);
            Callback c=callbacks.get(ctor);
            if(c!=null) c.after(new HookBridge.Call(object,args));
        }
    }
    public static void main(String[] args) throws Throwable {
        Bridge b=new Bridge(); GuardEngine.install(HookIntegrationTest.class.getClassLoader(),b);
        Field singleton=GuardEngine.class.getDeclaredField("instance"); singleton.setAccessible(true);
        Object engine=singleton.get(null);
        set(engine,"interactive",true); set(engine,"batteryAllowed",true);
        set(engine,"thermalStatus",1); set(engine,"sampledAt",1000L);
        DisplayPowerController main=new DisplayPowerController(0,"main");
        b.constructed(main,new Class<?>[]{int.class,String.class},0,"main");
        DisplayPowerController rear=new DisplayPowerController(1,"rear");
        b.constructed(rear,new Class<?>[]{int.class,String.class},1,"rear");
        DisplayPowerControllerImpl mi=new DisplayPowerControllerImpl(main);
        DisplayPowerControllerImpl rearMi=new DisplayPowerControllerImpl(rear);
        b.constructed(mi,new Class<?>[]{DisplayPowerController.class},main);
        b.constructed(rearMi,new Class<?>[]{DisplayPowerController.class},rear);
        Method adjust=mi.getClass().getDeclaredMethod("adjustBrightnessByThermal",float.class);
        check((Float)b.call(adjust,mi,500f)==300f,"default observation preserves 500 to 300 cap");
        set(engine,"enabled",true);
        check((Float)b.call(adjust,mi,500f)==500f,"main request survives the heat cap");
        check(mi.originalCalls==2,"original Xiaomi method still runs once per call");
        check((Float)b.call(adjust,rearMi,500f)==300f,"rear display is not changed");
        set(mi,"mDisplayPowerController",rear);
        check((Float)b.call(adjust,mi,500f)==300f,"live rear parent wins over a previously cached main ID");
        set(mi,"mDisplayPowerController",main);
        DisplayPowerControllerImpl unknown=new DisplayPowerControllerImpl(null);
        check((Float)b.call(adjust,unknown,500f)==300f,"unknown identity is not changed");
        check(!b.callbacks.containsKey(mi.getClass().getDeclaredMethod("adjustBrightnessByPower",float.class)),"nonthermal power method untouched");
        check(!b.callbacks.containsKey(mi.getClass().getDeclaredMethod("adjustBrightnessByThermal",int.class)),"unverified Xiaomi signature untouched");
        mi.fail=true;
        try { b.call(adjust,mi,500f); throw new AssertionError("original exception suppressed"); }
        catch(RuntimeException e) { check(e==mi.originalError,"original exception preserved"); }
        mi.fail=false;
        BrightnessThermalModifier modifier=new BrightnessThermalModifier("main");
        b.constructed(modifier,new Class<?>[]{String.class},"main");
        Method apply=modifier.getClass().getDeclaredMethod("apply",DisplayManagerInternal.DisplayPowerRequest.class,DisplayBrightnessState.Builder.class);
        Method aggregate=modifier.getClass().getDeclaredMethod("applyStateChange",BrightnessClamperController.ModifiersAggregatedState.class);
        DisplayBrightnessState.Builder state=new DisplayBrightnessState.Builder();
        b.call(apply,modifier,new DisplayManagerInternal.DisplayPowerRequest(),state);
        check(state.max==.7f && state.brightness==.7f && state.reason==2 && state.otherModifier==8,"other modifier cap/reason preserved");
        check(modifier.calls==0,"only thermal apply omitted");
        BrightnessClamperController.ModifiersAggregatedState totals=new BrightnessClamperController.ModifiersAggregatedState();
        b.call(aggregate,modifier,totals);
        check(totals.mMaxBrightness==.7f && totals.mMaxBrightnessReason==2,"other aggregated cap preserved");
        set(engine,"thermalStatus",3);
        state=new DisplayBrightnessState.Builder(); b.call(apply,modifier,new DisplayManagerInternal.DisplayPowerRequest(),state);
        check(state.max==.6f && state.brightness==.6f && modifier.calls==1,"severe heat restores original apply");
        set(engine,"thermalStatus",1); set(engine,"batteryAllowed",false);
        check((Float)b.call(adjust,mi,500f)==300f,"battery guard restores original cap");
        set(engine,"batteryAllowed",true); set(engine,"sampledAt",1L);
        // A stale sample requires a test clock value beyond its maximum age.
        set(engine,"sampledAt",-1L);
        check((Float)b.call(adjust,mi,500f)==300f,"missing sample restores original cap");
        set(engine,"sampledAt",1000L); set(engine,"enabled",false);
        check((Float)b.call(adjust,mi,500f)==300f,"switch off restores original result");
        set(engine,"enabled",true); set(modifier,"mUniqueDisplayId","rear");
        state=new DisplayBrightnessState.Builder(); b.call(apply,modifier,new DisplayManagerInternal.DisplayPowerRequest(),state);
        check(state.max==.6f,"current display identity supersedes historic constructor mapping");
        check(!b.callbacks.containsKey(modifier.getClass().getDeclaredMethod("apply",String.class)),"unverified AOSP signature untouched");
        BrightnessThrottler throttler=new BrightnessThrottler("main");
        Method getter=throttler.getClass().getDeclaredMethod("getBrightnessCap");
        check((Float)b.call(getter,throttler)==1f,"old thermal getter bypassed");
        check(Math.abs(Reflect.number(throttler,"mBrightnessCap")-.6)<.00001,"underlying thermal state retained");
        set(throttler,"mBrightnessCap",500f);
        check((Float)b.call(getter,throttler)==500f,"unexpected numeric domain retained");
        Object mainClamper=Reflect.read(main,"mBrightnessClamperController"), rearClamper=Reflect.read(rear,"mBrightnessClamperController");
        Method clamp=mainClamper.getClass().getDeclaredMethod("clamp",DisplayBrightnessState.class,
                DisplayManagerInternal.DisplayPowerRequest.class,float.class,boolean.class,int.class);
        DisplayBrightnessState stateObject=new DisplayBrightnessState();
        check(b.call(clamp,mainClamper,stateObject,new DisplayManagerInternal.DisplayPowerRequest(),.85f,false,2)==stateObject,"brightness observer preserves original return object");
        BrightnessMonitor monitor=(BrightnessMonitor)Reflect.read(engine,"monitor");
        check(Math.abs(monitor.collect(null,"main",2000,true,1,Collections.emptyList()).optDouble("request_0_1",0)-.85)<.0001,"observer captures main pre-cap request");
        b.call(clamp,rearClamper,stateObject,new DisplayManagerInternal.DisplayPowerRequest(),.3f,false,2);
        check(Math.abs(monitor.collect(null,"main",2001,true,1,Collections.emptyList()).optDouble("request_0_1",0)-.85)<.0001,"rear request does not contaminate main brightness monitor");
        System.out.println("PASS "+checks+" simulated display hook checks (not device tests)");
    }
}
