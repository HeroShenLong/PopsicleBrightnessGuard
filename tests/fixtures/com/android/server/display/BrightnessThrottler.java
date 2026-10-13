package com.android.server.display;
public final class BrightnessThrottler {
    private final String mUniqueDisplayId;
    private float mBrightnessCap=.6f;
    private int mBrightnessMaxReason=1;
    public BrightnessThrottler(String unique) { mUniqueDisplayId=unique; }
    public float getBrightnessCap() { return mBrightnessCap; }
    public int getBrightnessMaxReason() { return mBrightnessMaxReason; }
    public boolean isThrottled() { return true; }
}
