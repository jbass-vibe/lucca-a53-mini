package com.luccaa53mini;

import java.util.TimeZone;

/**
 * IS1Device — the contract between the UI and any S1 transport implementation.
 * <p>
 * Both BleManager (real hardware) and StubS1Device (developer test mode) implement
 * this interface. The activities hold an IS1Device reference and are completely
 * unaware of which implementation is active at runtime.
 * </p>
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
     * Full sync: reset sync control → enable sync control → write 84-byte schedule.
     * @param schedule The S1Schedule to synchronize.
     * @param tz       The target TimeZone.
     */
    void syncSchedule(S1Schedule schedule, TimeZone tz);

    /** 
     * Write the schedule only, without resetting sync control. 
     * @param schedule The S1Schedule to write.
     */
    void writeScheduleOnly(S1Schedule schedule);

    /** Read the current schedule stored on the device. */
    void readSchedule();

    /** 
     * Read current phone time (in the given timezone) and write to device RTC. 
     * @param tz The target TimeZone.
     */
    void syncRtc(TimeZone tz);

    /** 
     * Write the scheduler master toggle (on/off) to the device. 
     * @param enabled True to enable the scheduler.
     */
    void writeSyncControl(boolean enabled);

    /** Read the current state of the scheduler master toggle. */
    void readSyncControl();

    /** Read the device's current RTC value. */
    void readRtc();

    /** Read the current brew boiler temperature. */
    void readBrewBoiler();

    /** Read the current steam boiler temperature. */
    void readSteamBoiler();

    // ── State ────────────────────────────────────────────────────────────────

    /** Returns current internal BLE state. */
    BleManager.State getState();
    
    /** Returns true if connected and ready for commands. */
    boolean isConnected();

    // ── Listener ─────────────────────────────────────────────────────────────

    /** Sets the callback listener. */
    void setListener(BleManager.Listener listener);

    // ── Device metadata ──────────────────────────────────────────────────────

    /** Human-readable label shown in the UI to identify this implementation. */
    String getDeviceLabel();

    /** True only for the stub; the UI uses this to show the DEV MODE banner. */
    boolean isStub();

    /** True if the device firmware supports temperature reading (v2.xxx+). */
    boolean supportsTemperature();
}
