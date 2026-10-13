package com.android.server.display;
public final class DisplayPowerController {
    private final int mDisplayId;
    private final String mUniqueDisplayId;
    private final com.android.server.display.brightness.clamper.BrightnessClamperController mBrightnessClamperController
            =new com.android.server.display.brightness.clamper.BrightnessClamperController();
    public DisplayPowerController(int id, String unique) { mDisplayId=id; mUniqueDisplayId=unique; }
    public void updatePowerState() {}
}
