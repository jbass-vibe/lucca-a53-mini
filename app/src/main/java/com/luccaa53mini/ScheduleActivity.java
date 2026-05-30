package com.luccaa53mini;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/**
 * The primary interface for managing the Lucca S1 weekly schedule.
 * Handles the display of time slots, conflict detection, and synchronization
 * with the physical machine over Bluetooth.
 */
public class ScheduleActivity extends AppCompatActivity implements BleManager.Listener {

    private IS1Device device;
    private S1Schedule hardwareSchedule = new S1Schedule(); // The cached truth from device

    /**
     * UI Item wrapper to abstract "Slots" into a more user-friendly concept.
     * Each entry represents a unique ON/OFF time pair that can be applied to multiple days.
     */
    private static class UiEntry {
        int onH = 7, onM = 0;
        int offH = 8, offM = 0;
        boolean[] days = new boolean[7]; // Sun=0 .. Sat=6

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UiEntry uiEntry = (UiEntry) o;
            return onH == uiEntry.onH && onM == uiEntry.onM && offH == uiEntry.offH && offM == uiEntry.offM && Arrays.equals(days, uiEntry.days);
        }

        @Override
        public int hashCode() {
            int h = Objects.hash(onH, onM, offH, offM);
            return 31 * h + Arrays.hashCode(days);
        }
    }
    
    private static final long RTC_DRIFT_THRESHOLD_MS = 2 * 60 * 1000;
    private final List<UiEntry> uiEntries = new ArrayList<>();
    private boolean isFullSync = false;
    private boolean isInitialized = false;
    private android.app.Dialog activeDialog;

    // Top bar
    private ImageView  ivBleStatus;
    private TextView   tvConnectionLabel;
    private TextView   tvDeviceRtc;
    private ImageButton btnSync;
    private ImageButton btnDisconnect;
    private ProgressBar syncSpinner;
    private TextView   tvSyncStatus;
    private TextView   tvHeaderSubtitle;
    private View       layoutSyncStatus;
    private SwitchCompat swMasterSync;
    private TextView     tvMasterSwitchLabel;

    // Boiler Temps
    private View         layoutBoilerTemps;
    private TextView     tvBrewTemp;
    private TextView     tvSteamTemp;

    // Dev mode
    private View       devPanel;

    // List container
    private LinearLayout slotsContainer;
    private Button       btnAddSlot;

    // Bottom bar
    private Button btnSaveSync;
    private ProgressBar btnSaveSpinner;

    /**
     * Enum defining the specific type of synchronization currently in progress.
     */
    private enum SyncType { NONE, SCHEDULE, CLOCK, MASTER_TOGGLE }
    private SyncType currentSyncType = SyncType.NONE;
    private int syncAttempts = 0;
    private S1Schedule pendingSchedule = null;
    private long pendingClockSyncTime = 0;

    /**
     * Called when the activity is first created.
     * Initializes UI components, listeners, and triggers initial device state reads.
     *
     * @param savedInstanceState Saved state bundle.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        device = App.device;
        if (device == null) { finish(); return; }
        device.setListener(this);

        bindViews();
        updateConnectionStatus(true);
        setupDevPanel();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (device != null) device.disconnect();
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });

        setInteractionEnabled(false);
        setSyncStatus("Reading device schedule…", true);
        device.readSchedule();
        device.readRtc();
        device.readSyncControl();

        if (device.supportsTemperature()) {
            mainHandler.postDelayed(pollTempsRunnable, 1000);
        } else {
            layoutBoilerTemps.setVisibility(View.GONE);
        }
    }

    /**
     * Binds UI components from the layout to local variables.
     */
    private void bindViews() {
        ivBleStatus       = findViewById(R.id.ivBleStatus);
        tvConnectionLabel = findViewById(R.id.tvConnectionLabel);
        tvDeviceRtc       = findViewById(R.id.tvDeviceRtc);
        btnSync           = findViewById(R.id.btnSync);
        btnDisconnect     = findViewById(R.id.btnDisconnect);
        syncSpinner       = findViewById(R.id.syncSpinner);
        tvSyncStatus      = findViewById(R.id.tvSyncStatus);
        swMasterSync      = findViewById(R.id.swMasterSync);
        tvMasterSwitchLabel = findViewById(R.id.tvMasterSwitchLabel);
        layoutBoilerTemps = findViewById(R.id.layoutBoilerTemps);
        tvBrewTemp        = findViewById(R.id.tvBrewTemp);
        tvSteamTemp       = findViewById(R.id.tvSteamTemp);
        slotsContainer    = findViewById(R.id.slotsContainer);
        btnAddSlot        = findViewById(R.id.btnAddSlot);
        btnSaveSync       = findViewById(R.id.btnSaveSync);
        btnSaveSpinner    = findViewById(R.id.btnSaveSpinner);
        devPanel          = findViewById(R.id.devPanel);
        tvHeaderSubtitle  = findViewById(R.id.tvHeaderSubtitle);
        layoutSyncStatus  = findViewById(R.id.layoutSyncStatus);

        btnDisconnect.setOnClickListener(v -> confirmDisconnect());
        btnSync.setOnClickListener(v -> { isFullSync = false; startClockSync(); });
        btnSaveSync.setOnClickListener(v -> checkAndPerformSync());
        btnAddSlot.setOnClickListener(v -> addNewEntry());
        
        swMasterSync.setOnCheckedChangeListener((v, checked) -> {
            updateMasterSwitchUi(checked);
            if (v.isPressed()) { // Only trigger if user manually flipped it
                isFullSync = false;
                currentSyncType = SyncType.MASTER_TOGGLE;
                syncAttempts = 1;
                performGenericWrite();
            }
        });
        
        btnSync.setVisibility(View.GONE);
        btnSaveSync.setEnabled(false);
    }

    /**
     * Sets up the developer tools panel if in dev mode.
     */
    private void setupDevPanel() {
        if (!App.devMode || !(device instanceof StubS1Device)) {
            devPanel.setVisibility(View.GONE);
            return;
        }
        devPanel.setVisibility(View.VISIBLE);
        StubS1Device stub = (StubS1Device) device;
        devPanel.findViewById(R.id.btnInjectWriteError).setOnClickListener(v -> stub.injectWriteError());
        devPanel.findViewById(R.id.btnInjectDrop).setOnClickListener(v -> stub.injectConnectionDrop());
        devPanel.findViewById(R.id.btnInjectCorrupt).setOnClickListener(v -> { setInteractionEnabled(false); device.readSchedule(); });
        devPanel.findViewById(R.id.btnInjectRtcDrift).setOnClickListener(v -> { setInteractionEnabled(false); stub.injectRtcDrift(-7); device.readRtc(); });
        devPanel.findViewById(R.id.btnForceRead).setOnClickListener(v -> {
            setInteractionEnabled(false);
            device.readSchedule();
            device.readRtc();
            device.readBrewBoiler();
            device.readSteamBoiler();
        });
        
        View btnFailVerify = devPanel.findViewById(R.id.btnInjectVerifyFail);
        if (btnFailVerify != null) {
            btnFailVerify.setOnClickListener(v -> {
                stub.injectVerifyFail();
                setSyncStatus("Next sync will fail verification", false);
            });
        }
    }

    // ── Logic ────────────────────────────────────────────────────────────────

    /**
     * Aggregates the 21 physical device slots into a smaller list of user-friendly entries.
     */
    private void loadUiEntriesFromHardware() {
        uiEntries.clear();
        Map<String, UiEntry> map = new HashMap<>();
        int[] hwToUi = {1, 2, 3, 4, 5, 6, 0};

        for (int hwDay = 0; hwDay < 7; hwDay++) {
            int uiDayIndex = hwToUi[hwDay];
            int s = 0;
            while (s < S1Schedule.SLOTS) {
                S1Schedule.TimeSlot ts = hardwareSchedule.slots[hwDay][s];
                if (ts.enabled) {
                    String key = String.format(Locale.US, "%02d:%02d-%02d:%02d", ts.onHour, ts.onMinute, ts.offHour, ts.offMinute);
                    UiEntry entry = map.get(key);
                    if (entry == null) {
                        entry = new UiEntry();
                        entry.onH = ts.onHour; entry.onM = ts.onMinute;
                        entry.offH = ts.offHour; entry.offM = ts.offMinute;
                        map.put(key, entry);
                        uiEntries.add(entry);
                    }
                    entry.days[uiDayIndex] = true;
                }
                s++;
            }
        }
        buildCards();
        hardwareSchedule = convertUiToHardware();
        checkAndSyncButton();
    }

    /** Adds a new empty schedule card to the UI. */
    private void addNewEntry() {
        uiEntries.add(new UiEntry());
        buildCards();
        checkAndSyncButton();
    }

    /**
     * Updates the text and visual state of the scheduler master switch.
     * @param enabled True if the scheduler is active.
     */
    private void updateMasterSwitchUi(boolean enabled) {
        tvMasterSwitchLabel.setText(enabled ? R.string.scheduler_master_enabled : R.string.scheduler_master_disabled);
        buildCards(); // Rebuild cards to apply grey-out if needed
    }

    /**
     * Rebuilds all schedule cards in the UI container.
     */
    private void buildCards() {
        slotsContainer.removeAllViews();
        boolean timersEnabled = swMasterSync.isChecked();
        for (UiEntry entry : uiEntries) {
            View card = createEntryCard(entry);
            if (!timersEnabled) {
                card.setAlpha(0.5f);
                card.setOnClickListener(v -> showEnableTimersDialog());
                if (card instanceof ViewGroup) {
                    disableChildInteractions((ViewGroup) card);
                }
            } else {
                card.setAlpha(1.0f);
            }
            slotsContainer.addView(card);
        }
        
        if (!timersEnabled) {
            btnAddSlot.setAlpha(0.5f);
            btnAddSlot.setOnClickListener(v -> showEnableTimersDialog());
        } else {
            btnAddSlot.setAlpha(1.0f);
            btnAddSlot.setOnClickListener(v -> addNewEntry());
        }
    }

    /**
     * Helper to recursively disable interactions for a view group.
     * @param layout The root layout to disable.
     */
    private void disableChildInteractions(ViewGroup layout) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            child.setClickable(false);
            child.setFocusable(false);
            if (child instanceof ViewGroup) {
                disableChildInteractions((ViewGroup) child);
            }
        }
    }

    /**
     * Shows a dialog prompting the user to enable all timers before editing.
     */
    private void showEnableTimersDialog() {
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.timers_disabled_dialog_title)
            .setMessage(R.string.timers_disabled_dialog_message)
            .setPositiveButton(R.string.timers_disabled_dialog_enable, (dialog, which) -> {
                swMasterSync.setChecked(true);
                updateMasterSwitchUi(true);
                // Perform the sync
                currentSyncType = SyncType.MASTER_TOGGLE;
                syncAttempts = 1;
                performGenericWrite();
            })
            .setNegativeButton(R.string.timers_disabled_dialog_cancel, null)
            .show();
    }

    /**
     * Inflates and configures a single schedule card view.
     * @param entry The data for this card.
     * @return The configured view.
     */
    private View createEntryCard(UiEntry entry) {
        View card = getLayoutInflater().inflate(R.layout.item_slot_card, slotsContainer, false);
        Button btnOn = card.findViewById(R.id.btnOnTime);
        Button btnOff = card.findViewById(R.id.btnOffTime);
        ImageButton btnDel = card.findViewById(R.id.btnDelete);
        LinearLayout daysBox = card.findViewById(R.id.daysContainer);
        TextView tvConflict = card.findViewById(R.id.tvConflict);

        updateTimeButton(btnOn, entry.onH, entry.onM, "ON");
        updateTimeButton(btnOff, entry.offH, entry.offM, "OFF");
        checkConflict(tvConflict, entry);

        btnOn.setOnClickListener(v -> pickTime(entry.onH, entry.onM, (h, m) -> {
            if (!isBefore(h, m, entry.offH, entry.offM)) { showTimeRangeError(); return; }
            int conflictDay = getConflictDay(entry, h, m, entry.offH, entry.offM);
            if (conflictDay != -1) { showOverlapWarning(conflictDay); return; }
            entry.onH = h; entry.onM = m;
            updateTimeButton(btnOn, h, m, "ON");
            checkConflict(tvConflict, entry);
            checkAndSyncButton();
        }));

        btnOff.setOnClickListener(v -> pickTime(entry.offH, entry.offM, (h, m) -> {
            if (!isBefore(entry.onH, entry.onM, h, m)) { showTimeRangeError(); return; }
            int conflictDay = getConflictDay(entry, entry.onH, entry.onM, h, m);
            if (conflictDay != -1) { showOverlapWarning(conflictDay); return; }
            entry.offH = h; entry.offM = m;
            updateTimeButton(btnOff, h, m, "OFF");
            checkConflict(tvConflict, entry);
            checkAndSyncButton();
        }));

        btnDel.setOnClickListener(v -> {
            dismissActiveDialog();
            activeDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Remove Scheduled Time")
                .setMessage("Are you sure you want to remove this scheduled ON/OFF time? This will apply to all days selected on this card.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    uiEntries.remove(entry);
                    buildCards();
                    checkAndSyncButton();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        for (int i = 0; i < 7; i++) {
            final int dayIdx = i;
            TextView dayBtn = (TextView) getLayoutInflater().inflate(R.layout.view_day_checkbox, daysBox, false);
            dayBtn.setText(S1Schedule.DAY_SHORT[i].substring(0, 1));
            dayBtn.setSelected(entry.days[i]);
            dayBtn.setTextColor(entry.days[i] ? 0xFFFFFFFF : 0xFF888888);
            
            dayBtn.setOnClickListener(v -> {
                boolean newState = !dayBtn.isSelected();
                if (newState) {
                    if (!canAddSlotToDay(dayIdx)) { showMaxSlotsError(dayIdx); return; }
                    if (hasOverlapOnDay(entry, entry.onH, entry.onM, entry.offH, entry.offM, dayIdx)) {
                        showOverlapWarning(dayIdx); return;
                    }
                }
                entry.days[dayIdx] = newState;
                dayBtn.setSelected(newState);
                dayBtn.setTextColor(newState ? 0xFFFFFFFF : 0xFF888888);
                checkAndSyncButton();
            });
            daysBox.addView(dayBtn);
        }
        return card;
    }

    /** Checks if a day still has available hardware slots (max 3). */
    private boolean canAddSlotToDay(int dayIdx) {
        int count = 0;
        for (UiEntry e : uiEntries) if (e.days[dayIdx]) count++;
        return count < 3;
    }

    /** Identifies if a specific time range conflicts with any other scheduled day. */
    private int getConflictDay(UiEntry target, int onH, int onM, int offH, int offM) {
        for (int d = 0; d < 7; d++) if (target.days[d] && hasOverlapOnDay(target, onH, onM, offH, offM, d)) return d;
        return -1;
    }

    /** Checks if a time range overlaps with any other entry on a specific day. */
    private boolean hasOverlapOnDay(UiEntry targetEntry, int onH, int onM, int offH, int offM, int dayIdx) {
        int tS = onH * 60 + onM, tE = offH * 60 + offM;
        boolean tW = tE <= tS;
        for (UiEntry other : uiEntries) {
            if (other == targetEntry || !other.days[dayIdx]) continue;
            int oS = other.onH * 60 + other.onM, oE = other.offH * 60 + other.offM;
            boolean oW = oE <= oS;
            if (rangesConflict(tS, tE, tW, oS, oE, oW)) return true;
        }
        return false;
    }

    /** Determines if two time ranges (possibly wrapping midnight) intersect. */
    private boolean rangesConflict(int s1, int e1, boolean w1, int s2, int e2, boolean w2) {
        if (!w1 && !w2) return s1 <= e2 && s2 <= e1;
        List<int[]> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        if (w1) { r1.add(new int[]{s1, 1440}); r1.add(new int[]{0, e1}); } else r1.add(new int[]{s1, e1});
        if (w2) { r2.add(new int[]{s2, 1440}); r2.add(new int[]{0, e2}); } else r2.add(new int[]{s2, e2});
        for (int[] p1 : r1) for (int[] p2 : r2) if (p1[0] <= p2[1] && p2[0] <= p1[1]) return true;
        return false;
    }

    /** Shows overlap warning dialog. */
    private void showOverlapWarning(int dayIdx) {
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Time Conflict").setMessage("This time period overlaps or conflicts with an existing schedule on " + S1Schedule.DAY_NAMES[dayIdx] + ". \n\nEnsure that your ON and OFF times don't occur while the boiler is already scheduled to be active.")
                .setPositiveButton("Got it", null).show();
    }

    /** Shows maximum slots reached dialog. */
    private void showMaxSlotsError(int dayIdx) {
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Limit Reached").setMessage(S1Schedule.DAY_NAMES[dayIdx] + " already has the maximum (3) scheduled times. Remove an existing scheduled time before adding a new one.")
                .setPositiveButton("OK", null).show();
    }

    /** Helper to check time order. */
    private boolean isBefore(int h1, int m1, int h2, int m2) {
        return (h1 * 60 + m1) < (h2 * 60 + m2);
    }

    /** Shows invalid time range error. */
    private void showTimeRangeError() {
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Invalid Time Range").setMessage("The boiler ON time must be earlier than the OFF time. Please adjust the times to ensure a valid operating period.")
                .setPositiveButton("OK", null).show();
    }

    /** Updates button text with formatted time. */
    private void updateTimeButton(Button b, int h, int m, String prefix) {
        java.util.Calendar cal = java.util.Calendar.getInstance(); cal.set(2024, 0, 1, h, m);
        b.setText(String.format(Locale.US, "%s %s", prefix, android.text.format.DateFormat.getTimeFormat(this).format(cal.getTime())));
    }

    /** Toggles conflict visibility if times are identical. */
    private void checkConflict(TextView tv, UiEntry entry) { tv.setVisibility((entry.onH == entry.offH && entry.onM == entry.offM) ? View.VISIBLE : View.GONE); }

    /** Triggers system time picker dialog. */
    private void pickTime(int h, int m, TimePickCallback cb) {
        dismissActiveDialog();
        activeDialog = new TimePickerDialog(this, (v, hour, min) -> cb.onTimePicked(hour, min), h, m, android.text.format.DateFormat.is24HourFormat(this));
        activeDialog.show();
    }

    /** Simple callback for time picker results. */
    interface TimePickCallback { void onTimePicked(int h, int m); }

    // ── Comparison & State ──────────────────────────────────────────────────

    /**
     * Checks if the local UI schedule differs from the hardware cache and updates sync button.
     */
    private void checkAndSyncButton() {
        boolean changed = isScheduleChanged();
        btnSaveSync.setEnabled(changed);
        if (changed) {
            btnSaveSync.setText(R.string.btn_save_sync);
            btnSaveSync.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFD4813A));
        } else {
            btnSaveSync.setText(R.string.btn_synced);
            btnSaveSync.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2E7D32));
        }
    }

    /** Returns true if current UI entries differ from hardware state. */
    private boolean isScheduleChanged() {
        S1Schedule current = convertUiToHardware();
        for (int d = 0; d < 7; d++) {
            for (int s = 0; s < 3; s++) {
                if (!Objects.equals(current.slots[d][s], hardwareSchedule.slots[d][s])) return true;
            }
        }
        return false;
    }

    /** Converts the aggregated UI entries back into a flat 21-slot S1Schedule. */
    private S1Schedule convertUiToHardware() {
        S1Schedule hw = new S1Schedule();
        int[] dCounts = new int[7], uiToHw = {6, 0, 1, 2, 3, 4, 5};
        for (UiEntry e : uiEntries) {
            for (int uiDay = 0; uiDay < 7; uiDay++) {
                if (e.days[uiDay]) {
                    int hwDayIndex = uiToHw[uiDay];
                    int slotIdx = dCounts[hwDayIndex];
                    if (slotIdx < 3) {
                        S1Schedule.TimeSlot ts = hw.slots[hwDayIndex][slotIdx];
                        ts.enabled = true; ts.onHour = e.onH; ts.onMinute = e.onM; ts.offHour = e.offH; ts.offMinute = e.offM;
                        dCounts[hwDayIndex]++;
                    }
                }
            }
        }
        return hw;
    }

    // ── BLE Sync & Verification ──────────────────────────────────────────────

    /** Validates the schedule and initiates a BLE write. */
    private void checkAndPerformSync() {
        for (UiEntry e : uiEntries) {
            boolean anyDay = false;
            for (boolean d : e.days) if (d) { anyDay = true; break; }
            if (!anyDay) {
                dismissActiveDialog();
                activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Incomplete Schedule")
                        .setMessage("One of your scheduled times doesn't have any days selected. Please select at least one day or remove the schedule card before syncing.")
                        .setPositiveButton("OK", null).show();
                return;
            }
        }
        isFullSync = true;
        pendingSchedule = convertUiToHardware();
        startClockSync();
    }

    /** Initiates a clock synchronization sequence. */
    private void startClockSync() {
        currentSyncType = SyncType.CLOCK;
        
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        pendingClockSyncTime = cal.getTimeInMillis();

        syncAttempts = 1;
        performGenericWrite();
    }

    /**
     * Executes the appropriate device write based on currentSyncType.
     */
    private void performGenericWrite() {
        setInteractionEnabled(false);
        String label;
        switch (currentSyncType) {
            case SCHEDULE:
                label = "schedule";
                break;
            case CLOCK:
                label = "clock";
                break;
            default:
                label = "sync control";
                break;
        }
        setSyncStatus(String.format(Locale.US, "Syncing %s…", label), true);

        switch (currentSyncType) {
            case SCHEDULE:
                btnSaveSpinner.setVisibility(View.VISIBLE);
                btnSaveSync.setAlpha(0.5f);
                device.syncSchedule(pendingSchedule, TimeZone.getDefault());
                break;
            case CLOCK:
                if (isFullSync) {
                    btnSaveSpinner.setVisibility(View.VISIBLE);
                    btnSaveSync.setAlpha(0.5f);
                }
                device.syncRtc(TimeZone.getDefault());
                break;
            case MASTER_TOGGLE:
                device.writeSyncControl(swMasterSync.isChecked());
                break;
        }
    }

    /** Toggles interaction state for all UI components. */
    private void setInteractionEnabled(boolean enabled) {
        btnSync.setEnabled(enabled);
        swMasterSync.setEnabled(enabled);
        btnAddSlot.setEnabled(enabled);
        btnDisconnect.setEnabled(enabled);
        
        if (enabled) {
            btnSaveSpinner.setVisibility(View.GONE);
            btnSaveSync.setAlpha(1.0f);
            btnSaveSync.setVisibility(View.VISIBLE);
            checkAndSyncButton();
        } else {
            btnSaveSync.setEnabled(false);
            if (!isInitialized) {
                btnSaveSync.setVisibility(View.INVISIBLE);
            }
        }
        
        for (int i = 0; i < slotsContainer.getChildCount(); i++) {
            View card = slotsContainer.getChildAt(i);
            card.setEnabled(enabled);
            if (card instanceof ViewGroup) {
                card.setAlpha(enabled ? 1.0f : 0.5f);
            }
        }
    }

    /** Updates the status banner message. */
    private void setSyncStatus(String msg, boolean spinning) {
        mainHandler.removeCallbacks(resetHeaderTask);
        tvSyncStatus.setText(msg);
        
        // Show sync status container, hide static header
        tvHeaderSubtitle.setVisibility(View.GONE);
        layoutSyncStatus.setVisibility(View.VISIBLE);
        syncSpinner.setVisibility(spinning ? View.VISIBLE : View.GONE);

        if (!spinning) {
            mainHandler.postDelayed(resetHeaderTask, 2000);
        }
    }

    private final Runnable resetHeaderTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                layoutSyncStatus.setVisibility(View.GONE);
                tvHeaderSubtitle.setVisibility(View.VISIBLE);
            }
        }
    };

    /** Updates connection status UI helper. */
    private void updateConnectionStatus(boolean connected) {
        updateConnectionStatus(connected, false);
    }

    /** Updates connection status with failure support. */
    private void updateConnectionStatus(boolean connected, boolean syncFailed) {
        if (syncFailed) {
            ivBleStatus.setImageResource(R.drawable.ic_ble_error);
            tvConnectionLabel.setText(R.string.state_sync_failed);
            tvConnectionLabel.setTextColor(0xFFE53935);
        } else {
            ivBleStatus.setImageResource(connected ? R.drawable.ic_ble_dot_green : R.drawable.ic_ble_error);
            tvConnectionLabel.setText(connected ? (App.devMode ? "Connected [DEV]" : "Connected") : "Disconnected");
            tvConnectionLabel.setTextColor(connected ? 0xFF4CAF50 : 0xFFE53935);
        }
    }

    /** Shows disconnect confirmation dialog. */
    private void confirmDisconnect() {
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Disconnect or Exit")
                .setMessage("Would you like to disconnect from the machine and return to the scan screen, or exit the application entirely?")
                .setPositiveButton("Exit App", (d, w) -> { device.disconnect(); finishAffinity(); })
                .setNeutralButton("Disconnect", (d, w) -> { device.disconnect(); finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** {@inheritDoc} */
    @Override public void onScanStarted() {}
    /** {@inheritDoc} */
    @Override public void onDeviceFound(String n, String a) {}
    /** {@inheritDoc} */
    @Override public void onScanTimeout() {}
    /** {@inheritDoc} */
    @Override public void onScanFailed(int c) {}
    /** {@inheritDoc} */
    @Override public void onConnecting() {}
    
    /** {@inheritDoc} */
    @Override public void onConnected() { 
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            updateConnectionStatus(true);
        }); 
    }
    
    /** {@inheritDoc} */
    @Override public void onServicesDiscovered() {}
    
    /** {@inheritDoc} */
    @Override public void onDisconnected() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setInteractionEnabled(true);
            updateConnectionStatus(false);
            dismissActiveDialog();
            activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Disconnected")
                    .setMessage(App.devMode ? "Stub connection dropped." : "Your machine has disconnected.")
                    .setPositiveButton("Scan Again", (d, w) -> { finish(); startActivity(new Intent(this, ScanActivity.class)); })
                    .setNegativeButton("Stay", null).show();
        });
    }
    
    /** {@inheritDoc} */
    @Override public void onConnectionFailed(String r) { 
        runOnUiThread(() -> { 
            if (isFinishing() || isDestroyed()) return;
            setInteractionEnabled(true); 
            updateConnectionStatus(false); 
            setSyncStatus("Connection failed: " + r, false); 
        }); 
    }
    
    /** {@inheritDoc} */
    @Override public void onScheduleRead(byte[] raw) { 
        runOnUiThread(() -> { 
            if (isFinishing() || isDestroyed()) return;
            S1Schedule readBack = S1Schedule.fromBytes(raw);
            if (currentSyncType == SyncType.SCHEDULE) {
                byte[] pendingBytes = pendingSchedule.toBytes();
                if (Arrays.equals(raw, pendingBytes)) {
                    setSyncStatus("✓ Schedule verified", false);
                    hardwareSchedule = pendingSchedule;
                    pendingSchedule = null;
                    currentSyncType = SyncType.NONE;
                    isFullSync = false;
                    setInteractionEnabled(true);
                    if (App.devMode && App.getStub() != null) App.getStub().clearVerifyFail();
                } else {
                    if (App.isDebuggable()) {
                        logScheduleMismatch(raw, pendingBytes);
                    }
                    handleSyncRetry();
                }
            } else {
                hardwareSchedule = readBack;
                loadUiEntriesFromHardware(); 
                setSyncStatus("✓ Schedule loaded", false); 
                isInitialized = true;
                setInteractionEnabled(true);
            }
        }); 
    }

    /** Logs the exact byte differences during a failed verification. */
    private void logScheduleMismatch(byte[] read, byte[] pending) {
        Log.e("ScheduleSync", "Verification failed. Byte mismatch:");
        for (int i = 0; i < 7; i++) {
            byte[] rDay = Arrays.copyOfRange(read, i * 12, (i + 1) * 12);
            byte[] pDay = Arrays.copyOfRange(pending, i * 12, (i + 1) * 12);
            if (!Arrays.equals(rDay, pDay)) {
                Log.d("ScheduleSync", String.format(Locale.US, "Day %d (%s) mismatch!", i, S1Schedule.DAY_NAMES[i]));
                Log.d("ScheduleSync", "  Read:    " + Arrays.toString(rDay));
                Log.d("ScheduleSync", "  Pending: " + Arrays.toString(pDay));
            }
        }
    }

    /** Manages the retry logic for failed sync operations (up to 3 attempts). */
    private void handleSyncRetry() {
        if (syncAttempts < 3) {
            syncAttempts++;
            mainHandler.postDelayed(this::performGenericWrite, 1500);
        } else {
            setSyncStatus("Sync failed after 3 attempts", false);
            updateConnectionStatus(true, true); // Still connected, but sync failed
            showSyncFailureDialog();
            currentSyncType = SyncType.NONE;
            isFullSync = false;
            pendingSchedule = null;
            pendingClockSyncTime = 0;
            setInteractionEnabled(true);
        }
    }

    /** Shows final sync failure dialog. */
    private void showSyncFailureDialog() {
        String item = (currentSyncType == SyncType.SCHEDULE) ? "schedule" : "clock settings";
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Synchronization Error")
                .setMessage("We tried to update your " + item + " 3 times, but the machine is not confirming the change.\n\nPlease check your connection and try again.")
                .setPositiveButton("OK", null)
                .show();
    }
    
    /** {@inheritDoc} */
    @Override public void onScheduleWritten() { 
        runOnUiThread(() -> { 
            if (isFinishing() || isDestroyed()) return;
            if (currentSyncType == SyncType.SCHEDULE) {
                mainHandler.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    device.readSchedule();
                }, 800);
            } else {
                setSyncStatus("✓ Schedule saved", false); 
                checkAndSyncButton();
            }
        }); 
    }
    
    /** {@inheritDoc} */
    @Override public void onSyncControlRead(boolean e) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            swMasterSync.setChecked(e);
            updateMasterSwitchUi(e);
            if (currentSyncType == SyncType.MASTER_TOGGLE) {
                setSyncStatus("✓ Scheduler " + (e ? "enabled" : "disabled"), false);
                currentSyncType = SyncType.NONE;
            }
            setInteractionEnabled(true);
        });
    }

    /** {@inheritDoc} */
    @Override public void onSyncControlWritten() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (currentSyncType == SyncType.MASTER_TOGGLE) {
                mainHandler.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    device.readSyncControl();
                }, 500);
            }
        });
    }
    
    /** {@inheritDoc} */
    @Override public void onRtcRead(int[] dt) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (dt == null || dt.length < 7) {
                setInteractionEnabled(true);
                return;
            }
            java.util.Calendar devTime = java.util.Calendar.getInstance();
            devTime.clear();
            devTime.set(java.util.Calendar.YEAR, 2000 + dt[0]);
            devTime.set(java.util.Calendar.MONTH, dt[1] - 1);
            devTime.set(java.util.Calendar.DAY_OF_MONTH, dt[2]);
            devTime.set(java.util.Calendar.HOUR_OF_DAY, dt[4]);
            devTime.set(java.util.Calendar.MINUTE, dt[5]);
            devTime.set(java.util.Calendar.SECOND, 0);
            
            java.util.Calendar now = java.util.Calendar.getInstance();
            now.set(java.util.Calendar.SECOND, 0);
            now.set(java.util.Calendar.MILLISECOND, 0);
            
            if (currentSyncType == SyncType.CLOCK) {
                long diff = Math.abs(pendingClockSyncTime - devTime.getTimeInMillis());
                if (diff < 30 * 1000) {
                    setSyncStatus("✓ Clock verified", false);
                    btnSync.setVisibility(View.GONE);
                    
                    if (isFullSync) {
                        currentSyncType = SyncType.SCHEDULE;
                        syncAttempts = 1;
                        performGenericWrite();
                    } else {
                        currentSyncType = SyncType.NONE;
                        pendingClockSyncTime = 0;
                        updateConnectionStatus(true, false);
                        setInteractionEnabled(true);
                    }

                    if (App.devMode && App.getStub() != null) App.getStub().clearVerifyFail();
                } else {
                    handleSyncRetry();
                }
            } else if (!isFullSync) {
                setInteractionEnabled(true);
            }

            String timeStr = android.text.format.DateFormat.getTimeFormat(this).format(devTime.getTime());
            if (!android.text.format.DateFormat.is24HourFormat(this)) {
                timeStr = timeStr.replace(" AM", "AM").replace(" PM", "PM");
            }
            String dateStr = android.text.format.DateFormat.getDateFormat(this).format(devTime.getTime());
            String s = String.format("Espresso Clock: %s %s", timeStr, dateStr);
            if (App.devMode) s += " [STUB]";
            tvDeviceRtc.setText(s); tvDeviceRtc.setVisibility(View.VISIBLE);
            
            if (currentSyncType == SyncType.NONE) {
                if (Math.abs(now.getTimeInMillis() - devTime.getTimeInMillis()) > RTC_DRIFT_THRESHOLD_MS) {
                    btnSync.setVisibility(View.VISIBLE);
                    btnSync.setEnabled(true);
                    btnSync.setAlpha(1.0f);
                    promptRtcSync();
                } else {
                    btnSync.setVisibility(View.GONE);
                }
            }
        });
    }

    /** Prompts the user to update the machine clock if drift is detected. */
    private void promptRtcSync() {
        if (isFinishing() || isDestroyed()) return;
        dismissActiveDialog();
        activeDialog = new MaterialAlertDialogBuilder(this).setTitle("Update Clock").setMessage("Your espresso machine's internal clock has drifted and no longer matches your phone. \n\nTo ensure your boiler turns on exactly when you expect it to, would you like to synchronize the machine's time now?")
                .setPositiveButton("OK", (d, w) -> startClockSync()).setNegativeButton("Later", null).show();
    }

    /** {@inheritDoc} */
    @Override public void onRtcWritten() { 
        runOnUiThread(() -> { 
            if (isFinishing() || isDestroyed()) return;
            if (currentSyncType == SyncType.CLOCK) {
                mainHandler.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    device.readRtc();
                }, 800);
            } else {
                setSyncStatus("✓ Clock synced", false);
                device.readRtc(); 
            }
        }); 
    }

    /** {@inheritDoc} */
    @Override
    public void onBrewTempRead(double temp) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            layoutBoilerTemps.setVisibility(View.VISIBLE);
            tvBrewTemp.setText(String.format(Locale.US, "B:%.1f°C", temp));
        });
    }

    /** {@inheritDoc} */
    @Override
    public void onSteamTempRead(double temp) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            layoutBoilerTemps.setVisibility(View.VISIBLE);
            tvSteamTemp.setText(String.format(Locale.US, "S:%.1f°C", temp));
        });
    }

    /** {@inheritDoc} */
    @Override public void onError(String msg) { 
        runOnUiThread(() -> { 
            if (isFinishing() || isDestroyed()) return;
            setInteractionEnabled(true); 
            setSyncStatus("Error: " + msg, false); 
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); 
        }); 
    }

    /** Closes any visible dialogs to prevent memory leaks. */
    private void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
    }

    /**
     * Performs final cleanup when activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(pollTempsRunnable);
        dismissActiveDialog();
        if (device != null) {
            device.setListener(null);
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    
    /**
     * Polling mechanism to periodically refresh boiler temperatures.
     */
    private final Runnable pollTempsRunnable = new Runnable() {
        @Override
        public void run() {
            if (device != null && device.isConnected() && device.supportsTemperature()) {
                device.readBrewBoiler();
                device.readSteamBoiler();
                mainHandler.postDelayed(this, 30000);
            }
        }
    };
}
