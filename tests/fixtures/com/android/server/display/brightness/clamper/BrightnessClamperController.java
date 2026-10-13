package com.android.server.display.brightness.clamper;
public final class BrightnessClamperController {
    public com.android.server.display.DisplayBrightnessState clamp(com.android.server.display.DisplayBrightnessState state,
            android.hardware.display.DisplayManagerInternal.DisplayPowerRequest request,float brightness,boolean slow,int displayState) { return state; }
    public static class ModifiersAggregatedState { public float mMaxBrightness=.7f; public int mMaxBrightnessReason=2; }
}
