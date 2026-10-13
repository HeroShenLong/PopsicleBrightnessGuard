package android.os;
public class Handler {
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable task, long delay) { return true; }
    public boolean post(Runnable task) { return true; }
}
