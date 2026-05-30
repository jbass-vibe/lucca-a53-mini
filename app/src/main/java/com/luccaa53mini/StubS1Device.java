package com.luccaa53mini;

import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * StubS1Device — developer test mode implementation of IS1Device.
 * Simulates BLE hardware behavior for rapid UI development and testing.
 */
public class StubS1Device implements IS1Device {

    private static final long DELAY_SCAN_FOUND       =  1_800;
    private static final long DELAY_CONNECTING        =    600;
    private static final long DELAY_CONNECTED         =    400;
    private static final long DELAY_SERVICES          =    700;
    private static final long DELAY_GATT_OP           =    120;
    private static final long DELAY_WRITE_SCHEDULE    =    250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BleManager.Listener listener;
    private BleManager.State state = BleManager.State.IDLE;

    private boolean schedulerEnabled = true;
    private S1Schedule storedSchedule = buildSampleSchedule();
    private long rtcOffsetMs = -37_000L;
    private boolean pendingDropConnection = false;
    private boolean pendingWriteError = false;
    private boolean pendingVerifyFail = false;

    /** {@inheritDoc} */
    @Override public void setListener(BleManager.Listener l) { this.listener = l; }
    /** {@inheritDoc} */
    @Override public BleManager.State getState() { return state; }
    /** {@inheritDoc} */
    @Override public boolean isConnected() { return state == BleManager.State.CONNECTED; }
    /** {@inheritDoc} */
    @Override public String getDeviceLabel() { return "LUCCA STUB  [DEV MODE]"; }
    /** {@inheritDoc} */
    @Override public boolean isStub() { return true; }
    /** {@inheritDoc} */
    @Override public boolean supportsTemperature() { return true; }

    /** {@inheritDoc} */
    @Override public void startScan() {
        state = BleManager.State.SCANNING;
        post(() -> { if (listener != null) listener.onScanStarted(); });
        postDelay(DELAY_SCAN_FOUND, () -> {
            if (state != BleManager.State.SCANNING) return;
            if (listener != null) listener.onDeviceFound("Lucca #STUB", "DE:AD:BE:EF:CA:FE");
            simulateConnect();
        });
    }

    /** {@inheritDoc} */
    @Override public void stopScan() {
        handler.removeCallbacksAndMessages(null);
        if (state == BleManager.State.SCANNING) state = BleManager.State.IDLE;
    }

    /** {@inheritDoc} */
    @Override public void reconnect() { state = BleManager.State.IDLE; startScan(); }

    /** {@inheritDoc} */
    @Override public void disconnect() {
        handler.removeCallbacksAndMessages(null);
        state = BleManager.State.DISCONNECTED;
        if (listener != null) listener.onDisconnected();
    }

    /**
     * Internal simulation of the GATT connection handshake.
     */
    private void simulateConnect() {
        state = BleManager.State.CONNECTING;
        postDelay(DELAY_CONNECTING, () -> {
            if (listener != null) listener.onConnecting();
            postDelay(DELAY_CONNECTED, () -> {
                state = BleManager.State.CONNECTED;
                if (listener != null) listener.onConnected();
                postDelay(DELAY_SERVICES, () -> { if (listener != null) listener.onServicesDiscovered(); });
            });
        });
    }

    /** {@inheritDoc} */
    @Override
    public void syncSchedule(S1Schedule schedule, TimeZone tz) {
        postDelay(DELAY_GATT_OP, () -> {
            if (listener != null) listener.onSyncControlWritten(); // Mock 0x00 write
            postDelay(DELAY_GATT_OP, () -> {
                if (listener != null) listener.onSyncControlWritten(); // Mock 0x01 write
                postDelay(DELAY_WRITE_SCHEDULE, () -> {
                    if (pendingWriteError) {
                        pendingWriteError = false;
                        if (listener != null) listener.onError("GATT Write Error (Simulated)");
                        return;
                    }
                    storedSchedule = S1Schedule.fromBytes(schedule.toBytes());
                    if (listener != null) listener.onScheduleWritten();
                    maybeDropConnection();
                });
            });
        });
    }

    /** {@inheritDoc} */
    @Override
    public void writeScheduleOnly(S1Schedule schedule) {
        postDelay(DELAY_WRITE_SCHEDULE, () -> {
            storedSchedule = S1Schedule.fromBytes(schedule.toBytes());
            if (listener != null) listener.onScheduleWritten();
        });
    }

    /** {@inheritDoc} */
    @Override
    public void readSchedule() {
        postDelay(DELAY_GATT_OP, () -> {
            if (pendingVerifyFail) {
                pendingVerifyFail = false; 
                if (listener != null) listener.onScheduleRead(buildSampleSchedule().toBytes());
            } else {
                if (listener != null) listener.onScheduleRead(storedSchedule.toBytes());
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void syncRtc(TimeZone tz) {
        postDelay(DELAY_GATT_OP, () -> {
            applyRtcSync(tz);
            if (listener != null) listener.onRtcWritten();
            postDelay(DELAY_GATT_OP, () -> { if (listener != null) listener.onRtcRead(buildRtcResponse()); });
        });
    }

    /** {@inheritDoc} */
    @Override
    public void readSyncControl() {
        postDelay(DELAY_GATT_OP, () -> { if (listener != null) listener.onSyncControlRead(schedulerEnabled); });
    }

    /** {@inheritDoc} */
    @Override
    public void writeSyncControl(boolean enabled) {
        postDelay(DELAY_GATT_OP, () -> {
            this.schedulerEnabled = enabled;
            if (listener != null) listener.onSyncControlWritten();
        });
    }

    /** {@inheritDoc} */
    @Override public void readRtc() { postDelay(DELAY_GATT_OP, () -> { if (listener != null) listener.onRtcRead(buildRtcResponse()); }); }

    /** {@inheritDoc} */
    @Override
    public void readBrewBoiler() {
        postDelay(DELAY_GATT_OP, () -> {
            if (listener != null) listener.onBrewTempRead(93.4);
        });
    }

    /** {@inheritDoc} */
    @Override
    public void readSteamBoiler() {
        postDelay(DELAY_GATT_OP, () -> {
            if (listener != null) listener.onSteamTempRead(122.1);
        });
    }

    // ── Fault injection ──────────────────────────────────────────────────────

    /** Triggers a GATT write error on the next schedule write. */
    public void injectWriteError() { pendingWriteError = true; }
    
    /** Simulates a Bluetooth disconnection after the next sync. */
    public void injectConnectionDrop() { pendingDropConnection = true; }
    
    /** Forces the next schedule read to return corrupt data (failed verification). */
    public void injectVerifyFail() { pendingVerifyFail = true; }
    
    /** Clears any pending verification failures. */
    public void clearVerifyFail() { pendingVerifyFail = false; }

    /** Populates the internal memory with random schedule data. */
    public void injectCorruptSchedule() {
        storedSchedule = new S1Schedule();
        for (int day = 0; day < S1Schedule.DAYS; day++) {
            for (int slot = 0; slot < S1Schedule.SLOTS; slot++) {
                if (Math.random() > 0.6) {
                    S1Schedule.TimeSlot ts = storedSchedule.slots[day][slot];
                    ts.enabled   = true;
                    ts.onHour    = (int)(Math.random() * 24);
                    ts.onMinute  = (int)(Math.random() * 60);
                    ts.offHour   = (int)(Math.random() * 24);
                    ts.offMinute = (int)(Math.random() * 60);
                }
            }
        }
    }

    /** Simulates clock drift on the stub device. */
    public void injectRtcDrift(int minutes) { rtcOffsetMs += (long) minutes * 60_000L; }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Generates a dummy RTC array based on current system time + offset. */
    private int[] buildRtcResponse() {
        long now = System.currentTimeMillis() + rtcOffsetMs;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        return new int[]{
                cal.get(Calendar.YEAR) - 2000,
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7, // Hardware mapping: Mon=0..Sun=6
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND)
        };
    }

    /** Resets the drift offset to 0. */
    private void applyRtcSync(TimeZone tz) {
        if (tz != null) {
            rtcOffsetMs = 0;
        }
    }

    /** Builds a default sample schedule for the stub. */
    private static S1Schedule buildSampleSchedule() {
        S1Schedule s = new S1Schedule();
        for (int d = 0; d < 5; d++) {
            s.slots[d][0].enabled = true;
            s.slots[d][0].onHour = 7; s.slots[d][0].onMinute = 30;
            s.slots[d][0].offHour = 8; s.slots[d][0].offMinute = 30;
        }
        return s;
    }

    /** Internal helper to simulate spontaneous disconnects. */
    private void maybeDropConnection() {
        if (!pendingDropConnection) return;
        pendingDropConnection = false;
        postDelay(400, () -> { state = BleManager.State.DISCONNECTED; if (listener != null) listener.onDisconnected(); });
    }

    private void post(Runnable r) { handler.post(r); }
    private void postDelay(long ms, Runnable r) { handler.postDelayed(r, ms); }
}
