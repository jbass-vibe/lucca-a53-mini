package com.luccaa53mini;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import com.luccaa53mini.BuildConfig;

/**
 * Main Application class for the Lucca BT Remote app.
 * Handles global state, developer mode configuration, and device implementation selection.
 */
public class App extends Application {

    private static final String PREFS      = "lucca_prefs";
    private static final String KEY_DEV    = "dev_mode_enabled";

    /** Shared device instance passed between activities. */
    public static IS1Device device;

    /** True when developer test mode (stub) is active. */
    public static boolean devMode = false;

    private static App instance;

    /**
     * Initializes the application and loads developer mode preferences.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Dev mode only allowed in debug builds
        if (isDebuggable()) {
            devMode = prefs().getBoolean(KEY_DEV, false);
        } else {
            devMode = false;
        }
    }

    /**
     * Checks if the current build is debuggable.
     * @return True if the build is debuggable.
     */
    public static boolean isDebuggable() {
        return BuildConfig.DEBUG;
    }

    // ── Dev mode toggle ──────────────────────────────────────────────────────

    /**
     * Toggles the developer mode setting and persists it.
     * @param enabled True to enable stub mode.
     */
    public static void setDevMode(boolean enabled) {
        if (!isDebuggable()) return;
        devMode = enabled;
        prefs().edit().putBoolean(KEY_DEV, enabled).apply();
    }

    /**
     * Factory method to create and return the appropriate IS1Device for the current mode.
     * @param ctx The context to use (usually activity context).
     * @return An instance of {@link BleManager} or {@link StubS1Device}.
     */
    public static IS1Device createDevice(Context ctx) {
        if (devMode) {
            device = new StubS1Device();
        } else {
            device = new BleManager(ctx);
        }
        return device;
    }

    // ── Stub accessor (safe cast) ────────────────────────────────────────────

    /**
     * Returns the current device implementation cast to a StubS1Device.
     * @return The stub instance, or null if using real BLE.
     */
    public static StubS1Device getStub() {
        if (device instanceof StubS1Device) return (StubS1Device) device;
        return null;
    }

    /** Returns the shared preferences for the application. */
    private static SharedPreferences prefs() {
        return instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
