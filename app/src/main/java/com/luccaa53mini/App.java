package com.luccaa53mini;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

public class App extends Application {

    private static final String PREFS      = "lucca_prefs";
    private static final String KEY_DEV    = "dev_mode_enabled";

    /** Shared device instance passed between activities. */
    public static IS1Device device;

    /** True when developer test mode is active. */
    public static boolean devMode = false;

    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        devMode = prefs().getBoolean(KEY_DEV, false);
    }

    // ── Dev mode toggle ──────────────────────────────────────────────────────

    public static void setDevMode(boolean enabled) {
        devMode = enabled;
        prefs().edit().putBoolean(KEY_DEV, enabled).apply();
    }

    /** Create and return the appropriate IS1Device for the current mode. */
    public static IS1Device createDevice(Context ctx) {
        if (devMode) {
            device = new StubS1Device();
        } else {
            device = new BleManager(ctx);
        }
        return device;
    }

    // ── Stub accessor (safe cast) ────────────────────────────────────────────

    public static StubS1Device getStub() {
        if (device instanceof StubS1Device) return (StubS1Device) device;
        return null;
    }

    private static SharedPreferences prefs() {
        return instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
