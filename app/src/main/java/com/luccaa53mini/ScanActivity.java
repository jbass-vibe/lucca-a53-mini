package com.luccaa53mini;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.widget.SwitchCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * The initial activity that handles Bluetooth scanning and connection to the Lucca S1 device.
 * It manages permissions, Bluetooth adapter state, and transitions to {@link ScheduleActivity}
 * once a connection is established.
 */
public class ScanActivity extends AppCompatActivity implements BleManager.Listener {

    private IS1Device device;

    // Views
    private ImageView    iconBle;
    private TextView     tvTitle;
    private TextView     tvDeviceInfo;
    private ProgressBar  spinner;
    private Button       btnPrimary;
    private Button       btnCancel;
    private LinearLayout logContainer;
    private ScrollView   logScroll;
    private TextView     tvLogHeader;

    // Dev mode views
    private View         devBanner;
    private View         devModeContainer;
    private SwitchCompat swDevMode;

    private static final int PERM_REQUEST = 101;

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean isFirstRun = true;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Called when the activity is first created.
     * Initializes the UI components and prepares the device implementation.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *                           previously being shut down then this Bundle contains the data it most
     *                           recently supplied in {@link #onSaveInstanceState}.
     *                           <b>Note: Otherwise it is null.</b>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        bindViews();
        applyDevModeUi();

        device = App.createDevice(this);
        device.setListener(this);

        setState(UiState.IDLE);

        // In dev mode skip BT checks and scan immediately
        if (App.devMode) {
            device.startScan();
        }
    }

    /**
     * Called when the activity will start interacting with the user.
     * Ensures the device is disconnected and resets the UI state.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Ensure we are disconnected when returning to the scan screen
        if (device != null) {
            device.disconnect();
        }
        
        if (!isFirstRun) {
            clearLogs();
            addLog("Disconnected.");
        }
        isFirstRun = false;

        setState(UiState.IDLE);
    }

    /**
     * Perform any final cleanup before an activity is destroyed.
     * Stops any ongoing scans and cleans up handlers.
     */
    @Override
    protected void onDestroy() {
        if (device != null) {
            device.setListener(null);
            device.stopScan();
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * Binds UI components from the layout to local variables.
     */
    private void bindViews() {
        iconBle      = findViewById(R.id.iconBle);
        tvTitle      = findViewById(R.id.tvTitle);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        spinner      = findViewById(R.id.spinner);
        btnPrimary   = findViewById(R.id.btnPrimary);
        btnCancel    = findViewById(R.id.btnCancel);
        logContainer = findViewById(R.id.logContainer);
        logScroll    = findViewById(R.id.logScroll);
        tvLogHeader  = findViewById(R.id.tvLogHeader);
        devBanner    = findViewById(R.id.devBanner);
        devModeContainer = findViewById(R.id.devModeContainer);
        swDevMode    = findViewById(R.id.swDevMode);
    }

    /**
     * Configures the developer mode UI based on build type and user preference.
     */
    private void applyDevModeUi() {
        if (!App.isDebuggable()) {
            devModeContainer.setVisibility(View.GONE);
            devBanner.setVisibility(View.GONE);
            return;
        }
        devModeContainer.setVisibility(View.VISIBLE);
        swDevMode.setChecked(App.devMode);
        devBanner.setVisibility(App.devMode ? View.VISIBLE : View.GONE);

        swDevMode.setOnCheckedChangeListener((v, checked) -> {
            App.setDevMode(checked);
            devBanner.setVisibility(checked ? View.VISIBLE : View.GONE);
            // Recreate the device with the new mode
            device.stopScan();
            device = App.createDevice(this);
            device.setListener(this);
            setState(UiState.IDLE);
            addLog("Dev mode " + (checked ? "ON — using stub S1 device" : "OFF — using real BLE"));
        });
    }

    // ── UI State machine ─────────────────────────────────────────────────────

    /**
     * Internal states for the scanning and connection process.
     */
    private enum UiState {
        IDLE, SCANNING, DEVICE_FOUND, CONNECTING, CONNECTED,
        TIMEOUT, PERMISSION_DENIED, BT_DISABLED, ERROR
    }

    /**
     * Simplified helper to set the UI state without extra parameters.
     *
     * @param s The target UI state.
     */
    private void setState(UiState s) { setState(s, null, null); }

    /**
     * Updates the UI elements based on the current scanning or connection state.
     *
     * @param s          The target UI state.
     * @param deviceName Optional name of the found device.
     * @param deviceAddr Optional MAC address of the found device.
     */
    private void setState(UiState s, String deviceName, String deviceAddr) {
        // addLog removed from here to reduce verbosity
        switch (s) {
            case IDLE:
                setIcon(R.drawable.ic_ble_search, false);
                tvTitle.setText(R.string.searching_message);
                tvDeviceInfo.setVisibility(View.INVISIBLE);
                spinner.setVisibility(View.INVISIBLE);
                btnPrimary.setText(R.string.btn_start_scan);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> {
                    device = App.createDevice(this); // Re-create on every manual start
                    device.setListener(this);
                    if (App.devMode) device.startScan();
                    else checkPermissionsAndScan();
                });
                btnCancel.setVisibility(View.INVISIBLE);
                break;

            case SCANNING:
                setIcon(R.drawable.ic_ble_search, true);
                tvTitle.setText(App.devMode ? "Stub scanning…" : getString(R.string.state_scanning));
                tvDeviceInfo.setVisibility(View.INVISIBLE);
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.INVISIBLE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_stop_scanning);
                btnCancel.setOnClickListener(v -> {
                    addLog("Scan stopped by user.");
                    device.stopScan();
                    setState(UiState.IDLE);
                });
                addLog(App.devMode
                        ? "Stub scan started (no real BLE)"
                        : "BLE scan started — scanning for S1 devices");
                break;

            case DEVICE_FOUND:
                setIcon(R.drawable.ic_ble_found, true);
                tvTitle.setText(App.devMode ? "Stub device found" : getString(R.string.state_found));
                tvDeviceInfo.setVisibility(View.VISIBLE);
                tvDeviceInfo.setText(String.format("%s\n%s", deviceName, deviceAddr));
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.INVISIBLE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_cancel);
                btnCancel.setOnClickListener(v -> {
                    addLog("Connection cancelled by user.");
                    device.disconnect();
                    setState(UiState.IDLE);
                });
                addLog("Found: " + deviceName + " [" + deviceAddr + "]");
                break;

            case CONNECTING:
                setIcon(R.drawable.ic_ble_found, true);
                tvTitle.setText(R.string.state_connecting);
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.INVISIBLE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_cancel);
                btnCancel.setOnClickListener(v -> {
                    addLog("Connection cancelled by user.");
                    device.disconnect();
                    setState(UiState.IDLE);
                });
                break;

            case CONNECTED:
                setIcon(R.drawable.ic_ble_connected, false);
                tvTitle.setText(R.string.state_connected);
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.INVISIBLE);
                btnCancel.setVisibility(View.INVISIBLE);
                break;

            case TIMEOUT:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_no_device);
                tvDeviceInfo.setVisibility(View.INVISIBLE);
                spinner.setVisibility(View.INVISIBLE);
                btnPrimary.setText(R.string.btn_try_again);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> checkPermissionsAndScan());
                btnCancel.setVisibility(View.INVISIBLE);
                addLog("Scan timed out (15 s)");
                break;

            case PERMISSION_DENIED:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_perm_required);
                spinner.setVisibility(View.INVISIBLE);
                btnPrimary.setText(R.string.btn_grant_perm);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> requestPermissions());
                btnCancel.setVisibility(View.INVISIBLE);
                break;

            case BT_DISABLED:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_bt_off);
                spinner.setVisibility(View.INVISIBLE);
                btnPrimary.setText(R.string.btn_retry);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> checkPermissionsAndScan());
                btnCancel.setVisibility(View.INVISIBLE);
                break;

            case ERROR:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_error);
                spinner.setVisibility(View.INVISIBLE);
                btnPrimary.setText(R.string.btn_retry);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> {
                    if (App.devMode) { device.startScan(); }
                    else checkPermissionsAndScan();
                });
                btnCancel.setVisibility(View.INVISIBLE);
                addLog("Error: " + deviceName);
                break;
        }
    }

    /**
     * Updates the main BLE icon and optionally starts a pulsing animation.
     *
     * @param resId The drawable resource ID.
     * @param pulse True to start the pulse animation, false to stop.
     */
    private void setIcon(int resId, boolean pulse) {
        iconBle.setImageResource(resId);
        if (pulse) {
            android.view.animation.Animation anim = android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.pulse);
            iconBle.startAnimation(anim);
        } else {
            iconBle.clearAnimation();
        }
    }

    // ── Log helper ────────────────────────────────────────────────────────────

    /**
     * Removes all entries from the connection log.
     */
    private void clearLogs() {
        logContainer.removeAllViews();
    }

    /**
     * Adds a new entry to the connection log UI.
     *
     * @param message The text to display in the log.
     */
    private void addLog(String message) {
        tvLogHeader.setVisibility(View.VISIBLE);
        logContainer.setVisibility(View.VISIBLE);
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.log_entry, message));
        tv.setTextSize(12f);
        tv.setTextColor(App.devMode ? 0xFF9B6FD4 : 0xFF888888);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(0, 4, 0, 4);
        logContainer.addView(tv);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    /**
     * Orchestrates the permission check and Bluetooth adapter verification before scanning.
     */
    private void checkPermissionsAndScan() {
        if (App.devMode) { device.startScan(); return; }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ba = bm != null ? bm.getAdapter() : null;
        if (ba == null || !ba.isEnabled()) {
            setState(UiState.BT_DISABLED);
            return;
        }
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        device.startScan();
    }

    /**
     * Checks if all required Bluetooth and location permissions are granted.
     *
     * @return True if all required permissions are granted.
     */
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)   == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Triggers the system permission request dialog for needed Bluetooth permissions.
     */
    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
    }

    /**
     * Callback for the result from requesting permissions.
     *
     * @param req          The request code passed in {@link #requestPermissions()}.
     * @param perms        The requested permissions.
     * @param results      The grant results for the corresponding permissions.
     */
    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQUEST) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) { setState(UiState.PERMISSION_DENIED); return; }
            }
            device.startScan();
        }
    }

    // ── BleManager.Listener ──────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override public void onScanStarted()  { addLog("Searching for machines..."); setState(UiState.SCANNING); }

    /** {@inheritDoc} */
    @Override public void onDeviceFound(String name, String address) {
        addLog("Machine found!");
        setState(UiState.DEVICE_FOUND, name, address);
    }

    /** {@inheritDoc} */
    @Override public void onScanTimeout()  { addLog("No machine found nearby."); setState(UiState.TIMEOUT); }

    /** {@inheritDoc} */
    @Override public void onScanFailed(int code) {
        addLog("Search failed.");
        setState(UiState.ERROR, "BLE scan failed (code " + code + ")", null);
    }

    /** {@inheritDoc} */
    @Override public void onConnecting()   { addLog("Connecting to machine..."); setState(UiState.CONNECTING); }

    /** {@inheritDoc} */
    @Override public void onConnected()    { addLog("Connected!"); setState(UiState.CONNECTED); }

    /** {@inheritDoc} */
    @Override public void onServicesDiscovered() {
        App.device = device;
        startActivity(new Intent(this, ScheduleActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** {@inheritDoc} */
    @Override public void onDisconnected() {
        mainHandler.post(() -> setState(UiState.IDLE));
    }

    /** {@inheritDoc} */
    @Override public void onConnectionFailed(String reason) {
        setState(UiState.ERROR, reason, null);
    }

    /** {@inheritDoc} */
    @Override public void onScheduleRead(byte[] raw)   {}
    /** {@inheritDoc} */
    @Override public void onScheduleWritten()          {}
    /** {@inheritDoc} */
    @Override public void onSyncControlRead(boolean e) {}
    /** {@inheritDoc} */
    @Override public void onSyncControlWritten()       {}
    /** {@inheritDoc} */
    @Override public void onRtcRead(int[] dt)          {}
    /** {@inheritDoc} */
    @Override public void onRtcWritten()               {}
    /** {@inheritDoc} */
    @Override public void onBrewTempRead(double temp)  {}
    /** {@inheritDoc} */
    @Override public void onSteamTempRead(double temp) {}

    /** {@inheritDoc} */
    @Override public void onError(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
