package com.android.server.display;
public final class DisplayPowerControllerImpl {
    private final DisplayPowerController mDisplayPowerController;
    public int originalCalls;
    public boolean fail;
    public RuntimeException originalError = new RuntimeException("original display failure");
    public DisplayPowerControllerImpl(DisplayPowerController controller) { mDisplayPowerController=controller; }
    public float adjustBrightnessByThermal(float request) {
        originalCalls++;
        if (fail) throw originalError;
        return Math.min(request, 300f);
    }
    public float adjustBrightnessByThermal(int request) { return 123f; }
    public float adjustBrightnessByPower(float request) { return 100f; }
}
