package com.android.server.display;
public final class DisplayBrightnessState {
    public static class Builder {
        public float max = .7f, brightness = .7f;
        public int reason = 2, otherModifier = 8;
    }
}
