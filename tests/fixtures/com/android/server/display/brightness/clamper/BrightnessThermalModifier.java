package com.android.server.display.brightness.clamper;
import com.android.server.display.DisplayBrightnessState;
import android.hardware.display.DisplayManagerInternal;
public final class BrightnessThermalModifier {
    private String mUniqueDisplayId;
    private float mBrightnessCap=.6f;
    public int calls;
    public BrightnessThermalModifier(String unique) { mUniqueDisplayId=unique; }
    public void apply(DisplayManagerInternal.DisplayPowerRequest request, DisplayBrightnessState.Builder state) {
        calls++; state.max=Math.min(state.max,mBrightnessCap);
        state.brightness=Math.min(state.brightness,mBrightnessCap); state.reason=1;
    }
    public void applyStateChange(BrightnessClamperController.ModifiersAggregatedState state) {
        calls++; state.mMaxBrightness=Math.min(state.mMaxBrightness,mBrightnessCap); state.mMaxBrightnessReason=1;
    }
    public void apply(String unknownSignature) { calls++; }
}
