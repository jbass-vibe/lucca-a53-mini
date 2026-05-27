package com.luccaa53mini;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.*;

/**
 * BleManager: implements the full S1 Timer BLE protocol.
 * <p>
 * Custom Service UUID : ACAB0001-67F5-479E-8711-B3B99198CE6C
 * Sync Control        : ACAB0002  Handle 0x0010  R/W  1 byte
 * Weekly Schedule     : ACAB0003  Handle 0x0013  R/W  84 bytes (variable on partial write)
 * RTC Set             : ACAB0004  Handle 0x0016  R/W  7 bytes
 * RTC Read            : ACAB0005  Handle 0x0019  R    7 bytes
 * Brew Temperature    : ACAB0006  R
 * Steam Temperature   : ACAB0007  R
 * </p>
 */
public class BleManager implements IS1Device {

    private static final String TAG = "BleManager";

    // ── UUIDs ───────────────────────────────────────────────────────────────
    
    /** The main S1 Service UUID. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("ACAB0001-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for enabling/disabling the scheduler. */
    public static final UUID CHAR_SYNC_CONTROL =
            UUID.fromString("ACAB0002-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for reading/writing the 84-byte weekly schedule. */
    public static final UUID CHAR_SCHEDULE =
            UUID.fromString("ACAB0003-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for setting the device Real-Time Clock. */
    public static final UUID CHAR_RTC_SET =
            UUID.fromString("ACAB0004-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for reading the device Real-Time Clock. */
    public static final UUID CHAR_RTC_READ =
            UUID.fromString("ACAB0005-67F5-479E-8711-B3B99198CE6C");

    /** Service UUID for temperature readings (v2.x firmware). */
    public static final UUID SERVICE_TEMP_UUID =
            UUID.fromString("ACAB0001-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for reading brew boiler temperature. */
    public static final UUID CHAR_BREW_TEMP =
            UUID.fromString("ACAB0006-67F5-479E-8711-B3B99198CE6C");
    
    /** Characteristic for reading steam boiler temperature. */
    public static final UUID CHAR_STEAM_TEMP =
            UUID.fromString("ACAB0007-67F5-479E-8711-B3B99198CE6C");

    private static final long SCAN_TIMEOUT_MS   = 30_000;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long OP_TIMEOUT_MS      =  5_000;

    // ── Callback interface ──────────────────────────────────────────────────

    /**
     * Interface for observing BLE events and protocol data.
     */
    public interface Listener {
        /** Called when a Bluetooth scan starts. */
        void onScanStarted();
        
        /**
         * Called when a target S1 device is discovered.
         * @param name    The device name.
         * @param address The Bluetooth MAC address.
         */
        void onDeviceFound(String name, String address);
        
        /** Called when the scan exceeds SCAN_TIMEOUT_MS. */
        void onScanTimeout();
        
        /** Called when a Bluetooth scan fails. */
        void onScanFailed(int errorCode);
        
        /** Called when GATT connection is initiated. */
        void onConnecting();
        
        /** Called when GATT connection is physically established. */
        void onConnected();
        
        /** Called when GATT services and characteristics are parsed. */
        void onServicesDiscovered();
        
        /** Called when the device disconnects or link is lost. */
        void onDisconnected();
        
        /** Called when connection cannot be established. */
        void onConnectionFailed(String reason);

        /** Called when the 84-byte schedule is read. */
        void onScheduleRead(byte[] raw84);
        
        /** Called when a schedule write is confirmed. */
        void onScheduleWritten();
        
        /** Called when the sync control bit is read. */
        void onSyncControlRead(boolean enabled);
        
        /** Called when a sync control write is confirmed. */
        void onSyncControlWritten();
        
        /** Called when the device RTC is read. */
        void onRtcRead(int[] dateTime);
        
        /** Called when RTC write is confirmed. */
        void onRtcWritten();

        /** Called when brew temperature is read. */
        void onBrewTempRead(double temp);
        
        /** Called when steam temperature is read. */
        void onSteamTempRead(double temp);

        /** Called when a protocol or GATT error occurs. */
        void onError(String message);
    }

    // ── State ───────────────────────────────────────────────────────────────

    /**
     * Internal manager states.
     */
    public enum State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private State state = State.IDLE;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice targetDevice;
    private boolean firmwareSupportsTemp = false;

    private BluetoothGattCharacteristic charSyncControl;
    private BluetoothGattCharacteristic charSchedule;
    private BluetoothGattCharacteristic charRtcSet;
    private BluetoothGattCharacteristic charRtcRead;
    private BluetoothGattCharacteristic charBrewTemp;
    private BluetoothGattCharacteristic charSteamTemp;

    // Pending operations queue
    private final Queue<Runnable> opQueue = new LinkedList<>();
    private boolean opInProgress = false;

    private int retryCount3E = 0;

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

    /**
     * Initializes the manager with the given context.
     * @param context The application or activity context.
     */
    public BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
    }

    /** Sets the observer listener. */
    @Override public void setListener(Listener l) { this.listener = l; }
    
    /** Returns the current internal state. */
    @Override public State getState() { return state; }
    
    /** Returns true if GATT is connected and active. */
    @Override public boolean isConnected() { return state == State.CONNECTED && gatt != null; }

    // ── Scan ────────────────────────────────────────────────────────────────

    /**
     * Starts a BLE scan for S1 devices.
     * Verifies Bluetooth adapter state before beginning.
     */
    @Override public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            notifyError("Bluetooth is not enabled");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyError("BLE scanner unavailable");
            return;
        }

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

    /**
     * Stops any ongoing BLE scan.
     */
    @Override public void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException while stopping scan: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    /**
     * Internal handler for scan timeout.
     */
    private void onScanTimeout() {
        stopScan();
        state = State.IDLE;
        mainHandler.post(() -> { if (listener != null) listener.onScanTimeout(); });
    }

    /**
     * Callback for discovery of BLE devices.
     */
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String addr = dev.getAddress();
            String name;
            try {
                name = (result.getScanRecord() != null) ? result.getScanRecord().getDeviceName() : null;
                if (name == null) {
                    name = dev.getName();
                }
            } catch (SecurityException ignored) {
                name = null;
            }

            // Identification: Service UUID ACAB0001 (Consistent with this kind of device)
            boolean matchedByUuid = false;
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (android.os.ParcelUuid pUuid : result.getScanRecord().getServiceUuids()) {
                    if (Objects.equals(SERVICE_UUID, pUuid.getUuid())) {
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

                // Detect firmware version support (v2.xxx or later supports temperature)
                firmwareSupportsTemp = false;
                if (name != null && name.toLowerCase().contains("v.")) {
                    try {
                        String[] parts = name.toLowerCase().split("v\\.");
                        if (parts.length > 1) {
                            String versionPart = parts[1].trim();
                            // Handles S1 v.2.0, S1 v.02.01, etc.
                            if (versionPart.startsWith("2") || versionPart.startsWith("02")) {
                                firmwareSupportsTemp = true;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                Log.d(TAG, "Firmware supports temperature: " + firmwareSupportsTemp);

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

    /**
     * Initiates a GATT connection to the specified device.
     * @param device The target BluetoothDevice.
     */
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
        retryCount3E = 0; 
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

    /** Retries connection to the last found device. */
    @Override public void reconnect() {
        if (targetDevice != null) connectTo(targetDevice);
        else startScan();
    }

    /**
     * Closes the GATT connection and releases resources.
     */
    @Override public void disconnect() {
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

    /**
     * Helper to clean up state after a failed connection attempt.
     */
    private void failConnection(String reason) {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
        state = State.ERROR;
        mainHandler.post(() -> { if (listener != null) listener.onConnectionFailed(reason); });
    }

    // ── GATT Callback ────────────────────────────────────────────────────────

    /**
     * Implementation of BluetoothGattCallback to handle BLE hardware events.
     */
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

                // Handle 0x3E (62) - Connection Failed to be Established
                if (status == 62 && retryCount3E < 3) {
                    retryCount3E++;
                    if (App.isDebuggable()) Log.w(TAG, "Connection failed with 0x3E. Retrying (" + retryCount3E + "/3)...");
                    mainHandler.post(() -> {
                        try { g.close(); } catch (SecurityException ignored) {}
                        if (gatt == g) gatt = null;
                        mainHandler.postDelayed(() -> {
                            if (targetDevice != null) {
                                try {
                                    gatt = targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                                } catch (SecurityException ignored) {}
                            }
                        }, 1000);
                    });
                    return; // Exit early to wait for retry
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
        public void onServicesDiscovered(@NonNull BluetoothGatt g, int status) {
            if (App.isDebuggable()) Log.d(TAG, "onServicesDiscovered - status: " + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError(getHumanReadableError(status, "discovery"));
                return;
            }

            // Android GATT discovery can sometimes be racy.
            // We'll add a significant delay to ensure everything is settled.
            mainHandler.postDelayed(() -> {
                if (gatt == null) return;

                Log.d(TAG, "--- RECURSIVE CHARACTERISTIC DISCOVERY START ---");
                for (BluetoothGattService s : gatt.getServices()) {
                    Log.d(TAG, "Service Found: " + s.getUuid().toString());
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        String uuidStr = c.getUuid().toString().toUpperCase();
                        int prop = c.getProperties();
                        String propStr = ((prop & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? "R " : "") +
                                         ((prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? "W " : "") +
                                         ((prop & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? "N " : "");
                        
                        Log.d(TAG, "  -> Characteristic: " + uuidStr + " [" + propStr + "]");

                        // Map by UUID suffix to be service-agnostic
                        if (uuidStr.contains("ACAB0002")) charSyncControl = c;
                        else if (uuidStr.contains("ACAB0003")) charSchedule = c;
                        else if (uuidStr.contains("ACAB0004")) charRtcSet = c;
                        else if (uuidStr.contains("ACAB0005")) charRtcRead = c;
                        else if (uuidStr.contains("ACAB0006")) charBrewTemp = c;
                        else if (uuidStr.contains("ACAB0007")) charSteamTemp = c;
                    }
                }
                Log.d(TAG, "--- RECURSIVE CHARACTERISTIC DISCOVERY END ---");

                if (App.isDebuggable()) {
                    Log.d(TAG, "Mapping results: Sync=" + (charSyncControl != null) +
                            ", Schedule=" + (charSchedule != null) +
                            ", Brew=" + (charBrewTemp != null) +
                            ", Steam=" + (charSteamTemp != null));
                }

                if (charSyncControl == null || charSchedule == null) {
                    Log.e(TAG, "Mandatory S1 characteristics (0002/0003) missing!");
                    failConnection("Required machine characteristics not found");
                    return;
                }

                if (listener != null) listener.onServicesDiscovered();
            }, 1200);
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c,
                                         int status) {
            onCharacteristicRead(g, c, c.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c,
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
                if (Objects.equals(CHAR_SYNC_CONTROL, uuid)) {
                    final boolean enabled = val != null && val.length > 0 && val[0] == 0x01;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onSyncControlRead(enabled);
                    });
                } else if (Objects.equals(CHAR_SCHEDULE, uuid)) {
                    final byte[] full = expandSchedule(val);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onScheduleRead(full);
                    });
                } else if (Objects.equals(CHAR_RTC_READ, uuid) || Objects.equals(CHAR_RTC_SET, uuid)) {
                    final int[] parsed = parseRtc(val);
                    if (App.isDebuggable()) {
                        Log.d(TAG, "RTC Read parsed: " + Arrays.toString(parsed));
                    }
                    mainHandler.post(() -> {
                        if (listener != null) listener.onRtcRead(parsed);
                    });
                } else if (Objects.equals(CHAR_BREW_TEMP, uuid) || Objects.equals(CHAR_STEAM_TEMP, uuid)) {
                    final double temp = parseTemperature(val);
                    mainHandler.post(() -> {
                        if (Objects.equals(CHAR_BREW_TEMP, uuid)) {
                            if (listener != null) listener.onBrewTempRead(temp);
                        } else {
                            if (listener != null) listener.onSteamTempRead(temp);
                        }
                    });
                }
                drainQueue();
            });
        }

        @Override
        public void onCharacteristicWrite(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c,
                                          int status) {
            mainHandler.removeCallbacks(opTimeoutRunnable);
            opInProgress = false;
            UUID uuid = c.getUuid();

            if (App.isDebuggable()) {
                Log.d(TAG, "BLE WRITE: " + uuid + " status: " + status);
            }

            mainHandler.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (Objects.equals(CHAR_SYNC_CONTROL, uuid) && listener != null)
                        listener.onSyncControlWritten();
                    else if (Objects.equals(CHAR_SCHEDULE, uuid) && listener != null)
                        listener.onScheduleWritten();
                    else if (Objects.equals(CHAR_RTC_SET, uuid) && listener != null)
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

    /**
     * Translates GATT status codes into human-friendly error messages.
     * @param status The status code.
     * @param opType The operation type (e.g., "read", "write").
     * @return A localized error string.
     */
    private String getHumanReadableError(int status, String opType) {
        String msg;
        switch (status) {
            case 8: // 0x08 Connection Timeout
                msg = "Connection lost. Device moved out of range or powered off. Please retry.";
                break;
            case 19: // 0x13 Remote User Terminated Connection
                msg = "Machine disconnected the link.";
                break;
            case 22: // 0x16 Connection Terminated by Local Host
                msg = "Connection terminated by host.";
                break;
            case 34: // 0x22 LL Response Timeout
                msg = "Link layer response timeout. Unexpected drop, please retry.";
                break;
            case 62: // 0x3E Connection Failed to be Established
                msg = "Connection failed to establish after multiple attempts.";
                break;
            case 133: // GATT_ERROR
                msg = "Bluetooth stack error. Solution: Toggle Bluetooth off and on, or restart your phone.";
                break;
            case 137: // GATT_AUTH_FAIL
                msg = "Pairing error. Solution: Unpair the machine in Android Bluetooth settings and try again.";
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
    
    /**
     * Adds an operation to the sequential GATT queue.
     * @param op The Runnable operation.
     */
    private void enqueue(Runnable op) {
        opQueue.add(op);
        if (!opInProgress) drainQueue();
    }

    /**
     * Executes the next operation in the queue if none is in progress.
     */
    private void drainQueue() {
        if (opInProgress || opQueue.isEmpty() || gatt == null) return;
        opInProgress = true;
        mainHandler.postDelayed(opTimeoutRunnable, OP_TIMEOUT_MS);
        Runnable next = opQueue.poll();
        if (next != null) next.run();
    }

    // ── Public protocol operations ───────────────────────────────────────────

    /**
     * Performs a full atomic synchronization of the schedule.
     * @param schedule The schedule to write.
     * @param tz       The target timezone.
     */
    @Override public void syncSchedule(S1Schedule schedule, java.util.TimeZone tz) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x00}));
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x01}));
        enqueue(() -> writeChar(charSchedule, schedule.toBytes()));
    }

    /**
     * Writes the schedule without resetting sync control.
     * @param schedule The schedule to write.
     */
    @Override public void writeScheduleOnly(S1Schedule schedule) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x00}));
        enqueue(() -> writeChar(charSyncControl, new byte[]{0x01}));
        enqueue(() -> writeChar(charSchedule, schedule.toBytes()));
    }

    /** Reads the current 84-byte schedule. */
    @Override public void readSchedule() {
        enqueue(() -> readChar(charSchedule));
    }

    /**
     * Synchronizes the machine RTC with the phone's current time.
     * @param tz The target timezone.
     */
    @Override public void syncRtc(java.util.TimeZone tz) {
        enqueue(() -> writeChar(charRtcSet, buildRtcPayload(tz)));
        enqueue(() -> readChar(charRtcRead));
    }

    /** Reads the sync control state. */
    @Override public void readSyncControl() {
        enqueue(() -> readChar(charSyncControl));
    }

    /**
     * Writes the sync control bit.
     * @param enabled True to enable the scheduler.
     */
    @Override public void writeSyncControl(boolean enabled) {
        enqueue(() -> writeChar(charSyncControl, new byte[]{enabled ? (byte) 0x01 : (byte) 0x00}));
    }

    /** Reads the device RTC. */
    @Override public void readRtc() {
        enqueue(() -> readChar(charRtcRead));
    }

    /** Reads the brew boiler temperature. */
    @Override public void readBrewBoiler() {
        enqueue(() -> readChar(charBrewTemp));
    }

    /** Reads the steam boiler temperature. */
    @Override public void readSteamBoiler() {
        enqueue(() -> readChar(charSteamTemp));
    }

    // ── Low-level GATT helpers ───────────────────────────────────────────────
    
    /**
     * Internal helper to trigger a GATT read.
     * @param c The characteristic to read.
     */
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

    /**
     * Internal helper to trigger a GATT write.
     * @param c     The characteristic to write.
     * @param value The byte array value.
     */
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
     * @param tz The target timezone.
     * @return The 7-byte payload.
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

    /**
     * Parses the 7-byte RTC response into integers.
     * @param b The raw bytes.
     * @return Array of [year, month, day, dow, hour, min, sec].
     */
    public static int[] parseRtc(byte[] b) {
        if (b == null || b.length < 7) return new int[7];
        return new int[]{
                b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF,
                b[4] & 0xFF, b[5] & 0xFF, b[6] & 0xFF
        };
    }

    /**
     * Parse 16-bit signed little-endian temperature from raw buffer.
     * Bytes 0-1: Temperature (value / 10.0)
     * @param b Raw GATT value.
     * @return Temperature in Celsius.
     */
    private static double parseTemperature(byte[] b) {
        if (b == null || b.length < 2) return 0.0;
        short raw = (short) ((b[0] & 0xFF) | ((b[1] & 0xFF) << 8));
        return raw / 10.0;
    }

    /** 
     * Expand a variable-length schedule response to the canonical 84 bytes.
     * @param raw The raw GATT value.
     * @return Padded 84-byte array.
     */
    private static byte[] expandSchedule(byte[] raw) {
        byte[] full = new byte[84];
        if (raw != null) System.arraycopy(raw, 0, full, 0, Math.min(raw.length, 84));
        return full;
    }

    // ── IS1Device metadata ───────────────────────────────────────────────────
    
    /** Returns the device display label (MAC address). */
    @Override public String getDeviceLabel() {
        return targetDevice != null ? targetDevice.getAddress() : "Lucca Espresso Machine";
    }
    
    /** Returns false. */
    @Override public boolean isStub() { return false; }
    
    /** Returns true if firmware version matches v2.x or later. */
    @Override public boolean supportsTemperature() { return firmwareSupportsTemp; }

    // ── Helpers ──────────────────────────────────────────────────────────────
    
    /** Logs an error and notifies the listener. */
    private void notifyError(String msg) {
        Log.e(TAG, msg);
        mainHandler.post(() -> { if (listener != null) listener.onError(msg); });
    }
}
