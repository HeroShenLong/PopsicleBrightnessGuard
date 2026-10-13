package io.github.popsicle.brightnessguard;

import java.lang.reflect.Executable;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

public final class ModernEntry extends XposedModule {
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        GuardEngine.install(param.getClassLoader(), new HookBridge() {
            @Override public void hook(Executable executable, Callback callback) {
                ModernEntry.this.hook(executable).intercept(chain -> {
                    Call call = new Call(chain.getThisObject(), chain.getArgs().toArray());
                    callback.before(call);
                    if (!call.skip) {
                        try { call.result = chain.proceed(); }
                        catch (Throwable original) { call.failure = original; }
                    }
                    call.changed = false;
                    callback.after(call);
                    if (call.failure != null) throw call.failure;
                    return call.result;
                });
            }
            @Override public void deoptimize(Executable executable) { ModernEntry.this.deoptimize(executable); }
            @Override public void log(String text) { Log.i("PopsicleBrightnessGuard", text); }
            @Override public String apiName() { return "modern-101"; }
        });
    }
}
