package com.luccaa53mini;

import java.util.TimeZone;

/**
 * IS1Device — the contract between the UI and any S1 transport implementation.
 *
 * Both BleManager (real hardware) and StubS1Device (developer test mode) implement
 * this interface. The activities hold an IS1Device reference and are completely
 * unaware of which implementation is active at runtime.
 */
public interface IS1Device {

    // ── Connection lifecycle ─────────────────────────────────────────────────

    /** Start scanning for an S1 device. */
    void startScan();

    /** Stop an in-progress scan. */
    void stopScan();

    /** Reconnect to the last known device, or restart scan if none. */
    void reconnect();

    /** Gracefully disconnect and release all resources. */
    void disconnect();

    // ── GATT / protocol operations ───────────────────────────────────────────

    /**
     * Full sync: reset sync control → enable sync control → write 84-byte schedule
     * → read device RTC → write phone time as RTC → read device RTC to confirm.
     */
    void syncSchedule(S1Schedule schedule, TimeZone tz);

    /** Write the schedule only, without touching the RTC. */
    void writeScheduleOnly(S1Schedule schedule);

    /** Read the current schedule stored on the device. */
    void readSchedule();

    /** Read current phone time (in the given timezone) to the device RTC. */
    void syncRtc(TimeZone tz);

    /** Write the scheduler master toggle (on/off) to the device. */
    void writeSyncControl(boolean enabled);

    /** Read the current state of the scheduler master toggle. */
    void readSyncControl();

    /** Read the device's current RTC value. */
    void readRtc();

    // ── State ────────────────────────────────────────────────────────────────

    BleManager.State getState();
    boolean isConnected();

    // ── Listener ─────────────────────────────────────────────────────────────

    void setListener(BleManager.Listener listener);

    // ── Device metadata ──────────────────────────────────────────────────────

    /** Human-readable label shown in the UI to identify this implementation. */
    String getDeviceLabel();

    /** True only for the stub; the UI uses this to show the DEV MODE banner. */
    boolean isStub();
}
