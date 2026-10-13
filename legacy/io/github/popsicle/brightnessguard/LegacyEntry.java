package io.github.popsicle.brightnessguard;

import java.lang.reflect.Executable;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LegacyEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"android".equals(param.packageName)
                || !("android".equals(param.processName) || "system".equals(param.processName))) return;
        GuardEngine.install(param.classLoader, new HookBridge() {
            @Override public void hook(Executable executable, Callback callback) {
                XposedBridge.hookMethod(executable, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Call call = new Call(param.thisObject, param.args);
                        callback.before(call);
                        if (call.skip) param.setResult(call.result);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Call call = new Call(param.thisObject, param.args);
                        call.failure = param.getThrowable();
                        call.result = param.getResult();
                        callback.after(call);
                        if (call.changed && call.failure == null) param.setResult(call.result);
                    }
                });
            }
            @Override public void deoptimize(Executable executable) { /* Legacy bridge has no stable API for this. */ }
            @Override public void log(String text) { XposedBridge.log("PopsicleBrightnessGuard: " + text); }
            @Override public String apiName() { return "legacy-82"; }
        });
    }
}
