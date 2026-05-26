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

public class ScanActivity extends AppCompatActivity implements BleManager.Listener {

    private IS1Device device;

    // Views
    private ImageView    iconBle;
    private TextView     tvTitle;
    private TextView     tvSubtitle;
    private TextView     tvDeviceInfo;
    private ProgressBar  spinner;
    private Button       btnPrimary;
    private Button       btnCancel;
    private LinearLayout logContainer;
    private ScrollView   logScroll;
    private TextView     tvLogHeader;

    // Dev mode views
    private View         devBanner;
    private SwitchCompat swDevMode;
    private TextView     tvDevLabel;

    private static final int PERM_REQUEST = 101;

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ── Lifecycle ────────────────────────────────────────────────────────────
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
        } else {
            addLog("Ready to scan.");
        }
    }

    @Override
    protected void onDestroy() {
        if (device != null) {
            device.setListener(null);
            device.stopScan();
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        iconBle      = findViewById(R.id.iconBle);
        tvTitle      = findViewById(R.id.tvTitle);
        tvSubtitle   = findViewById(R.id.tvSubtitle);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        spinner      = findViewById(R.id.spinner);
        btnPrimary   = findViewById(R.id.btnPrimary);
        btnCancel    = findViewById(R.id.btnCancel);
        logContainer = findViewById(R.id.logContainer);
        logScroll    = findViewById(R.id.logScroll);
        tvLogHeader  = findViewById(R.id.tvLogHeader);
        devBanner    = findViewById(R.id.devBanner);
        swDevMode    = findViewById(R.id.swDevMode);
        tvDevLabel   = findViewById(R.id.tvDevLabel);
    }

    private void applyDevModeUi() {
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
    private enum UiState {
        IDLE, SCANNING, DEVICE_FOUND, CONNECTING, CONNECTED,
        TIMEOUT, PERMISSION_DENIED, BT_DISABLED, ERROR
    }

    private void setState(UiState s) { setState(s, null, null); }

    private void setState(UiState s, String deviceName, String deviceAddr) {
        // addLog removed from here to reduce verbosity
        switch (s) {
            case IDLE:
                setIcon(R.drawable.ic_ble_search, false);
                tvTitle.setText(R.string.searching_message);
                tvSubtitle.setText(App.devMode
                        ? "Developer mode active. Tap Start to launch the stub."
                        : getString(R.string.state_idle_subtitle));
                tvDeviceInfo.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
                btnPrimary.setText(R.string.btn_start_scan);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> {
                    device = App.createDevice(this); // Re-create on every manual start
                    device.setListener(this);
                    if (App.devMode) device.startScan();
                    else checkPermissionsAndScan();
                });
                btnCancel.setVisibility(View.GONE);
                break;

            case SCANNING:
                setIcon(R.drawable.ic_ble_search, true);
                tvTitle.setText(App.devMode ? "Stub scanning…" : getString(R.string.state_scanning));
                tvSubtitle.setText(App.devMode
                        ? "Simulating BLE scan for S1 device."
                        : getString(R.string.state_scanning_subtitle));
                tvDeviceInfo.setVisibility(View.GONE);
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.GONE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_cancel);
                btnCancel.setOnClickListener(v -> {
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
                tvSubtitle.setText("Connecting automatically…");
                tvDeviceInfo.setVisibility(View.VISIBLE);
                tvDeviceInfo.setText(String.format("%s\n%s", deviceName, deviceAddr));
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.GONE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_cancel);
                btnCancel.setOnClickListener(v -> {
                    device.disconnect();
                    setState(UiState.IDLE);
                });
                addLog("Found: " + deviceName + " [" + deviceAddr + "]");
                break;

            case CONNECTING:
                setIcon(R.drawable.ic_ble_found, true);
                tvTitle.setText(R.string.state_connecting);
                tvSubtitle.setText(App.devMode
                        ? "Simulating GATT connection."
                        : "Establishing GATT connection.");
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.GONE);
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText(R.string.btn_cancel);
                btnCancel.setOnClickListener(v -> {
                    device.disconnect();
                    setState(UiState.IDLE);
                });
                break;

            case CONNECTED:
                setIcon(R.drawable.ic_ble_connected, false);
                tvTitle.setText(R.string.state_connected);
                tvSubtitle.setText("Discovering services…");
                spinner.setVisibility(View.VISIBLE);
                btnPrimary.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
                break;

            case TIMEOUT:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_no_device);
                tvSubtitle.setText(R.string.state_timeout_subtitle);
                tvDeviceInfo.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
                btnPrimary.setText(R.string.btn_try_again);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> checkPermissionsAndScan());
                btnCancel.setVisibility(View.GONE);
                addLog("Scan timed out (15 s)");
                break;

            case PERMISSION_DENIED:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_perm_required);
                tvSubtitle.setText(R.string.state_perm_subtitle);
                spinner.setVisibility(View.GONE);
                btnPrimary.setText(R.string.btn_grant_perm);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> requestPermissions());
                btnCancel.setVisibility(View.GONE);
                break;

            case BT_DISABLED:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_bt_off);
                tvSubtitle.setText(R.string.state_bt_off_subtitle);
                spinner.setVisibility(View.GONE);
                btnPrimary.setText(R.string.btn_retry);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> checkPermissionsAndScan());
                btnCancel.setVisibility(View.GONE);
                break;

            case ERROR:
                setIcon(R.drawable.ic_ble_error, false);
                tvTitle.setText(R.string.state_error);
                tvSubtitle.setText(deviceName != null ? deviceName : "An unexpected error occurred.");
                spinner.setVisibility(View.GONE);
                btnPrimary.setText(R.string.btn_retry);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setOnClickListener(v -> {
                    if (App.devMode) { device.startScan(); }
                    else checkPermissionsAndScan();
                });
                btnCancel.setVisibility(View.GONE);
                addLog("Error: " + deviceName);
                break;
        }
    }

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
    private void addLog(String message) {
        tvLogHeader.setVisibility(View.VISIBLE);
        logContainer.setVisibility(View.VISIBLE);
        TextView tv = new TextView(this);
        tv.setText("› " + message);
        tv.setTextSize(12f);
        tv.setTextColor(App.devMode ? 0xFF9B6FD4 : 0xFF888888);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(0, 4, 0, 4);
        logContainer.addView(tv);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ── Permissions ──────────────────────────────────────────────────────────
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

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)   == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
    }

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
    @Override public void onScanStarted()  { addLog("Searching for machines..."); setState(UiState.SCANNING); }

    @Override public void onDeviceDiscovered(String name, String address) {
        // Silent on UI log for individual discoveries to reduce noise
    }

    @Override public void onDeviceFound(String name, String address) {
        addLog("Machine found!");
        setState(UiState.DEVICE_FOUND, name, address);
    }

    @Override public void onScanTimeout()  { addLog("No machine found nearby."); setState(UiState.TIMEOUT); }

    @Override public void onScanFailed(int code) {
        addLog("Search failed.");
        setState(UiState.ERROR, "BLE scan failed (code " + code + ")", null);
    }

    @Override public void onConnecting()   { addLog("Connecting to machine..."); setState(UiState.CONNECTING); }
    @Override public void onConnected()    { addLog("Connected!"); setState(UiState.CONNECTED); }

    @Override public void onServicesDiscovered() {
        App.device = device;
        startActivity(new Intent(this, ScheduleActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override public void onDisconnected() {
        mainHandler.post(() -> {
            addLog("Disconnected.");
            setState(UiState.IDLE);
        });
    }

    @Override public void onConnectionFailed(String reason) {
        setState(UiState.ERROR, reason, null);
    }

    @Override public void onScheduleRead(byte[] raw)   {}
    @Override public void onScheduleWritten()          {}
    @Override public void onSyncControlRead(boolean e) {}
    @Override public void onSyncControlWritten()       {}
    @Override public void onRtcRead(int[] dt)          {}
    @Override public void onRtcWritten()               {}
    @Override public void onError(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
