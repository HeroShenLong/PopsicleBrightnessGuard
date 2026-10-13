package io.github.popsicle.brightnessguard;

import java.lang.reflect.Executable;

public interface HookBridge {
    void hook(Executable method, Callback callback) throws Throwable;
    void deoptimize(Executable method) throws Throwable;
    void log(String text);
    String apiName();

    interface Callback {
        default void before(Call call) throws Throwable {}
        default void after(Call call) throws Throwable {}
    }

    final class Call {
        public final Object receiver;
        public final Object[] args;
        public Object result;
        public Throwable failure;
        public boolean skip;
        public boolean changed;
        public Call(Object receiver, Object[] args) { this.receiver = receiver; this.args = args; }
        public void replace(Object value) { result = value; changed = true; }
        public void skipWith(Object value) { replace(value); skip = true; }
    }
}
