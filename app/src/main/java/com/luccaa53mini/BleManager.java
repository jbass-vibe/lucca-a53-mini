package com.luccaa53mini;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.*;

/**
 * BleManager: implements the full S1 Timer BLE protocol.
 *
 * Custom Service UUID : ACAB0001-67F5-479E-8711-B3B99198CE6C
 * Sync Control        : ACAB0002  Handle 0x0010  R/W  1 byte
 * Weekly Schedule     : ACAB0003  Handle 0x0013  R/W  84 bytes (variable on partial write)
 * RTC Set             : ACAB0004  Handle 0x0016  R/W  7 bytes
 * RTC Read            : ACAB0005  Handle 0x0019  R    7 bytes
 */
public class BleManager implements IS1Device {

    private static final String TAG = "BleManager";

    // ── UUIDs ───────────────────────────────────────────────────────────────
    public static final UUID SERVICE_UUID =
            UUID.fromString("ACAB0001-67F5-479E-8711-B3B99198CE6C");
    public static final UUID CHAR_SYNC_CONTROL =
            UUID.fromString("ACAB0002-67F5-479E-8711-B3B99198CE6C");
    public static final UUID CHAR_SCHEDULE =
            UUID.fromString("ACAB0003-67F5-479E-8711-B3B99198CE6C");
    public static final UUID CHAR_RTC_SET =
            UUID.fromString("ACAB0004-67F5-479E-8711-B3B99198CE6C");
    public static final UUID CHAR_RTC_READ =
            UUID.fromString("ACAB0005-67F5-479E-8711-B3B99198CE6C");

    private static final long SCAN_TIMEOUT_MS   = 30_000;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long OP_TIMEOUT_MS      =  5_000;

    // ── Callback interface ──────────────────────────────────────────────────
    public interface Listener {
        void onScanStarted();
        void onDeviceFound(String name, String address);      // Target found
        void onScanTimeout();
        void onScanFailed(int errorCode);
        void onConnecting();
        void onConnected();
        void onServicesDiscovered();
        void onDisconnected();
        void onConnectionFailed(String reason);

        void onScheduleRead(byte[] raw84);       // raw bytes from device
        void onScheduleWritten();
        void onSyncControlRead(boolean enabled);
        void onSyncControlWritten();
        void onRtcRead(int[] dateTime);           // [day,month,year,sub,hour,min,sec]
        void onRtcWritten();

        void onError(String message);
    }

    // ── State ───────────────────────────────────────────────────────────────
    public enum State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private State state = State.IDLE;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice targetDevice;

    private BluetoothGattCharacteristic charSyncControl;
    private BluetoothGattCharacteristic charSchedule;
    private BluetoothGattCharacteristic charRtcSet;
    private BluetoothGattCharacteristic charRtcRead;

    // Pending operations queue
    private final Queue<Runnable> opQueue = new LinkedList<>();
    private boolean opInProgress = false;

    private final Runnable scanTimeoutRunnable = this::onScanTimeout;
    private final Runnable connectTimeoutRunnable = () ->
            failConnection("Connection timed out");
    private final Runnable opTimeoutRunnable = () -> {
        opInProgress = false;
        if (App.isDebuggable()) {
            Log.w(TAG, "Operation timed out; skipping to next item in queue");
        }
        drainQueue();
    };

    // ── Construction ────────────────────────────────────────────────────────
    public BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
    }

    public void setListener(Listener l) { this.listener = l; }
    public State getState() { return state; }
    public boolean isConnected() { return state == State.CONNECTED && gatt != null; }

    // ── Scan ────────────────────────────────────────────────────────────────
    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            notifyError("Bluetooth is not enabled");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { notifyError("BLE scanner unavailable"); return; }

        state = State.SCANNING;
        mainHandler.post(() -> { if (listener != null) listener.onScanStarted(); });
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);

        // Broaden scan: scan for all devices and filter in callback
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "Scan started without filters");
        } catch (SecurityException e) {
            notifyError("Scan failed: permission missing");
        }
    }

    public void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while stopping scan: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    private void onScanTimeout() {
        stopScan();
        state = State.IDLE;
        mainHandler.post(() -> { if (listener != null) listener.onScanTimeout(); });
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String addr = dev.getAddress();
            String name = (result.getScanRecord() != null) ? result.getScanRecord().getDeviceName() : null;
            if (name == null) {
                try { name = dev.getName(); } catch (SecurityException ignored) {}
            }

            // Identification: Service UUID ACAB0001 (Consistent with this kind of device)
            boolean matchedByUuid = false;
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (android.os.ParcelUuid pUuid : result.getScanRecord().getServiceUuids()) {
                    if (SERVICE_UUID.equals(pUuid.getUuid())) {
                        matchedByUuid = true;
                        break;
                    }
                }
            }

            // Fallback: Name starts with S1
            boolean matchedByName = false;
            if (name != null) {
                String n = name.trim().toUpperCase();
                if (n.startsWith("S1")) {
                    matchedByName = true;
                }
            }

            if (matchedByUuid || matchedByName) {
                Log.d(TAG, "Matched S1 Device: " + name + " [" + addr + "] (UUID=" + matchedByUuid + ", Name=" + matchedByName + ")");
                stopScan();
                targetDevice = dev;
                String finalName = (name != null) ? name.trim() : "S1 Machine";
                mainHandler.post(() -> {
                    if (listener != null) listener.onDeviceFound(finalName, addr);
                });
                connectTo(dev);
            }
        }
        @Override
        public void onScanFailed(int errorCode) {
            state = State.IDLE;
            mainHandler.post(() -> {
                if (listener != null) listener.onScanFailed(errorCode);
            });
        }
    };

    // ── Connect ─────────────────────────────────────────────────────────────
    private boolean isConnectingWithAuto = false;

    public void connectTo(BluetoothDevice device) {
        if (gatt != null) {
            Log.d(TAG, "Closing existing GATT before new connection");
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while closing GATT: " + e.getMessage());
            }
            gatt = null;
        }
        state = State.CONNECTING;
        isConnectingWithAuto = false;
        mainHandler.post(() -> { if (listener != null) listener.onConnecting(); });
        mainHandler.removeCallbacks(connectTimeoutRunnable);
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);

        // Status 133 fix: ensure previous scans are stopped and add a settlement delay
        stopScan();

        mainHandler.postDelayed(() -> {
            Log.d(TAG, "Initiating GATT connection to " + device.getAddress());
            try {
                // Try initial connect with autoConnect=false for speed
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) {
                    failConnection("Failed to create GATT instance");
                }
            } catch (SecurityException e) {
                failConnection("Bluetooth permission missing: " + e.getMessage());
            }
        }, 1200); // Increased settlement delay
    }

    public void reconnect() {
        if (targetDevice != null) connectTo(targetDevice);
        else startScan();
    }

    public void disconnect() {
        mainHandler.removeCallbacks(connectTimeoutRunnable);
        opQueue.clear();
        opInProgress = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while disconnecting: " + e.getMessage());
            }
            gatt = null;
        }
        state = State.DISCONNECTED;
        mainHandler.post(() -> { if (listener != null) listener.onDisconnected(); });
    }

    private void failConnection(String reason) {
        if (gatt != null) { gatt.close(); gatt = null; }
        state = State.ERROR;
        mainHandler.post(() -> { if (listener != null) listener.onConnectionFailed(reason); });
    }

    // ── GATT Callback ────────────────────────────────────────────────────────
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            
            if (App.isDebuggable()) {
                Log.d(TAG, "onConnectionStateChange - status: " + status + " (" + getStatusString(status) + "), newState: " + newState);
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = State.CONNECTED;
                mainHandler.post(() -> {
                    if (listener != null) listener.onConnected();
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        notifyError("Permission missing for service discovery");
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // status 133 is often a stack congestion issue.
                // If it happens on first attempt, try once more with autoConnect=true.
                if (status == 133 && !isConnectingWithAuto && state == State.CONNECTING) {
                    if (App.isDebuggable()) Log.w(TAG, "Connect failed with 133. Retrying with autoConnect=true...");
                    isConnectingWithAuto = true;
                    mainHandler.post(() -> {
                        try {
                            g.close();
                        } catch (SecurityException ignored) {}
                        if (gatt == g) gatt = null;
                        mainHandler.postDelayed(() -> {
                            if (targetDevice != null) {
                                try {
                                    gatt = targetDevice.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE);
                                } catch (SecurityException ignored) {}
                            }
                        }, 1000);
                    });
                    return;
                }

                if (gatt == g) {
                    try {
                        gatt.close();
                    } catch (SecurityException ignored) {}
                    gatt = null;
                }
                boolean wasConnected = (state == State.CONNECTED);
                state = State.DISCONNECTED;
                mainHandler.post(() -> {
                    if (wasConnected && listener != null) listener.onDisconnected();
                    else if (listener != null) {
                        listener.onConnectionFailed(getHumanReadableError(status, "connection"));
                    }
                });
            }
        }

        private String getStatusString(int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) return "SUCCESS";
            if (status == 133) return "GATT_ERROR/STACK_CONGESTION";
            if (status == 8) return "GATT_INSUF_AUTHORIZATION";
            if (status == 19) return "GATT_CONN_TERMINATE_PEER_USER";
            if (status == 62) return "GATT_CONN_FAIL_ESTABLISH";
            return "CODE_" + status;
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (App.isDebuggable()) Log.d(TAG, "onServicesDiscovered - status: " + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError(getHumanReadableError(status, "discovery"));
                return;
            }

            // Debug: Log ALL services and characteristics
            for (BluetoothGattService s : g.getServices()) {
                Log.d(TAG, "Service: " + s.getUuid().toString());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    Log.d(TAG, "  Char: " + c.getUuid().toString() + " [" + c.getInstanceId() + "]");
                }
            }

            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                failConnection("Machine service not found on device (ACAB0001)");
                return;
            }
            charSyncControl = svc.getCharacteristic(CHAR_SYNC_CONTROL);
            charSchedule    = svc.getCharacteristic(CHAR_SCHEDULE);
            charRtcSet      = svc.getCharacteristic(CHAR_RTC_SET);
            charRtcRead     = svc.getCharacteristic(CHAR_RTC_READ);

            mainHandler.post(() -> { if (listener != null) listener.onServicesDiscovered(); });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         int status) {
            onCharacteristicRead(g, c, c.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         byte[] value, int status) {
            mainHandler.removeCallbacks(opTimeoutRunnable);
            opInProgress = false;
            
            UUID uuid = c.getUuid();
            byte[] val = (value != null) ? value : c.getValue();

            if (App.isDebuggable()) {
                Log.d(TAG, "BLE READ: " + uuid + " status: " + status + " val: " + Arrays.toString(val));
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> {
                    notifyError(getHumanReadableError(status, "read"));
                    opQueue.clear(); // Clear pending ops on failure
                    drainQueue();
                });
                return;
            }

            mainHandler.post(() -> {
                if (CHAR_SYNC_CONTROL.equals(uuid)) {
                    if (listener != null) listener.onSyncControlRead(val != null && val.length > 0 && val[0] == 0x01);
                } else if (CHAR_SCHEDULE.equals(uuid)) {
                    byte[] full = expandSchedule(val);
                    if (listener != null) listener.onScheduleRead(full);
                } else if (CHAR_RTC_READ.equals(uuid) || CHAR_RTC_SET.equals(uuid)) {
                    int[] parsed = parseRtc(val);
                    if (App.isDebuggable()) {
                        Log.d(TAG, "RTC Read parsed: " + Arrays.toString(parsed));
                    }
                    if (listener != null) listener.onRtcRead(parsed);
                }
                drainQueue();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c,
                                          int status) {
            mainHandler.removeCallbacks(opTimeoutRunnable);
            opInProgress = false;
            UUID uuid = c.getUuid();

            if (App.isDebuggable()) {
                Log.d(TAG, "BLE WRITE: " + uuid + " status: " + status);
            }

            mainHandler.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (CHAR_SYNC_CONTROL.equals(uuid) && listener != null)
                        listener.onSyncControlWritten();
                    else if (CHAR_SCHEDULE.equals(uuid) && listener != null)
                        listener.onScheduleWritten();
                    else if (CHAR_RTC_SET.equals(uuid) && listener != null)
                        listener.onRtcWritten();
                    drainQueue();
                } else {
                    notifyError(getHumanReadableError(status, "write"));
                    opQueue.clear(); // Abort sequence on failure
                    drainQueue();
                }
            });
        }
    };

    private String getHumanReadableError(int status, String opType) {
        String msg;
        switch (status) {
            case 133:
                msg = "Bluetooth stack error. Solution: Toggle Bluetooth off and on, or restart your phone.";
                break;
            case 8: // GATT_INSUF_AUTHORIZATION
            case 137: // GATT_AUTH_FAIL
                msg = "Pairing error. Solution: Unpair the machine in Android Bluetooth settings and try again.";
                break;
            case 19: // GATT_CONN_TERMINATE_PEER_USER
                msg = "Machine disconnected the link.";
                break;
            case 6: // GATT_NOT_FOUND
                msg = "Bluetooth service not found. Make sure you are connecting to the correct machine.";
                break;
            default:
                msg = "Bluetooth " + opType + " error (Code " + status + "). Solution: Try moving closer to the machine.";
                break;
        }
        return msg;
    }

    // ── Operation queue helpers ──────────────────────────────────────────────
    private void enqueue(Runnable op) {
        opQueue.add(op);
        if (!opInProgress) drainQueue();
    }

    private void drainQueue() {
        if (opInProgress || opQueue.isEmpty() || gatt == null) return;
        opInProgress = true;
        mainHandler.postDelayed(opTimeoutRunnable, OP_TIMEOUT_MS);
        Runnable next = opQueue.poll();
        if (next != null) next.run();
    }

    // ── Public protocol operations ───────────────────────────────────────────

    /** Full sync sequence: reset sync → enable sync → write schedule → write RTC */
    public void syncSchedule(S1Schedule schedule, java.util.TimeZone tz) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x00}));
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x01}));
        enqueue(() -> writeChar(charSchedule, schedule.toBytes()));
        // Note: We skip the RTC update here to avoid confusing status messages in UI
        // and to keep the schedule sync atomic.
    }

    /** Write the schedule without touching the RTC */
    public void writeScheduleOnly(S1Schedule schedule) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x00}));
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x01}));
        enqueue(() -> writeChar(charSchedule, schedule.toBytes()));
    }

    /** Read the current schedule from device */
    public void readSchedule() {
        enqueue(() -> readChar(charSchedule));
    }

    /** Sync only the RTC to current phone time in the given timezone */
    public void syncRtc(java.util.TimeZone tz) {
        enqueue(() -> writeChar(charRtcSet, buildRtcPayload(tz)));
        enqueue(() -> readChar(charRtcRead));
    }

    /** Read only the current sync control state */
    @Override
    public void readSyncControl() {
        enqueue(() -> readChar(charSyncControl));
    }

    /** Write only the sync control state (enable/disable scheduler) */
    @Override
    public void writeSyncControl(boolean enabled) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{enabled ? (byte) 0x01 : (byte) 0x00}));
    }

    /** Read current device RTC */
    public void readRtc() {
        enqueue(() -> readChar(charRtcRead));
    }

    // ── Low-level GATT helpers ───────────────────────────────────────────────
    private void readChar(BluetoothGattCharacteristic c) {
        if (gatt == null || c == null) { opInProgress = false; drainQueue(); return; }
        if (App.isDebuggable()) Log.d(TAG, "BLE QUEUE -> READ: " + c.getUuid());
        try {
            if (!gatt.readCharacteristic(c)) {
                Log.e(TAG, "readCharacteristic failed to initiate");
                opInProgress = false;
                drainQueue();
            }
        } catch (SecurityException e) {
            notifyError("SecurityException during read: " + e.getMessage());
            opInProgress = false;
            drainQueue();
        }
    }

    private void writeChar(BluetoothGattCharacteristic c, byte[] value) {
        if (gatt == null || c == null) { opInProgress = false; drainQueue(); return; }
        if (App.isDebuggable()) Log.d(TAG, "BLE QUEUE -> WRITE: " + c.getUuid() + " val: " + Arrays.toString(value));
        try {
            boolean success;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                success = result == 0; // BluetoothStatusCodes.SUCCESS
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(value);
                success = gatt.writeCharacteristic(c);
            }
            if (!success) {
                Log.e(TAG, "writeCharacteristic failed to initiate");
                opInProgress = false;
                drainQueue();
            }
        } catch (SecurityException e) {
            notifyError("SecurityException during write: " + e.getMessage());
            opInProgress = false;
            drainQueue();
        }
    }

    // ── RTC encoding ────────────────────────────────────────────────────────
    /**
     * Build the 7-byte RTC payload for the S1 device.
     * Format: [year_offset][month][day][dow][hour][min][sec]
     * year_offset is observed as calendar_year - 2000 (e.g., 2026 -> 26).
     * dow: 1=Sun, 2=Mon...7=Sat (Standard Java/Android Calendar values)
     */
    public static byte[] buildRtcPayload(java.util.TimeZone tz) {
        java.util.Calendar cal = java.util.Calendar.getInstance(tz);
        int year  = cal.get(java.util.Calendar.YEAR) - 2000;
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day   = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int dow   = cal.get(java.util.Calendar.DAY_OF_WEEK);
        
        int hour  = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int min   = cal.get(java.util.Calendar.MINUTE);
        int sec   = cal.get(java.util.Calendar.SECOND);
        
        return new byte[]{
                (byte) year, (byte) month, (byte) day, (byte) dow,
                (byte) hour, (byte) min, (byte) sec
        };
    }

    public static int[] parseRtc(byte[] b) {
        if (b == null || b.length < 7) return new int[7];
        return new int[]{
                b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF,
                b[4] & 0xFF, b[5] & 0xFF, b[6] & 0xFF
        };
    }

    /** Expand a variable-length schedule response to the canonical 84 bytes */
    private static byte[] expandSchedule(byte[] raw) {
        byte[] full = new byte[84];
        if (raw != null) System.arraycopy(raw, 0, full, 0, Math.min(raw.length, 84));
        return full;
    }

    // ── IS1Device metadata ───────────────────────────────────────────────────
    @Override public String getDeviceLabel() {
        return targetDevice != null ? targetDevice.getAddress() : "Lucca Espresso Machine";
    }
    @Override public boolean isStub() { return false; }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private void notifyError(String msg) {
        Log.e(TAG, msg);
        mainHandler.post(() -> { if (listener != null) listener.onError(msg); });
    }
}
