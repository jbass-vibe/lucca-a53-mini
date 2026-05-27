package com.luccaa53mini;

import android.content.Context;

/**
 * Represents the complete 7-day boiler schedule.
 * <p>
 * Wire format: 84 bytes = 7 days × 12 bytes = 7 days × 3 slots × 4 bytes.
 * Each 4-byte slot: [on_min][on_hour][off_min][flags_off_hour]
 *   flags_off_hour: bit7 = enabled (1=slot active), bits6-0 = off_hour
 * </p>
 */
public class S1Schedule {

    /** Total number of days in a week. */
    public static final int DAYS  = 7;
    
    /** Maximum number of programmable slots per day. */
    public static final int SLOTS = 3;

    /** Internal 2D array of time slots [day 0-6][slot 0-2]. */
    public final TimeSlot[][] slots = new TimeSlot[DAYS][SLOTS];

    /** Full names of the days of the week starting from Sunday. */
    public static final String[] DAY_NAMES =
            {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    
    /** Short names of the days of the week starting from Sunday. */
    public static final String[] DAY_SHORT =
            {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    /**
     * Initializes a blank schedule with all slots set to disabled.
     */
    public S1Schedule() {
        for (int d = 0; d < DAYS; d++)
            for (int s = 0; s < SLOTS; s++)
                slots[d][s] = new TimeSlot();
    }

    // ── Serialise to 84-byte wire format ────────────────────────────────────
    
    /**
     * Serializes the current schedule into the 84-byte BLE wire format.
     * @return 84-byte array representing the full weekly schedule.
     */
    public byte[] toBytes() {
        byte[] out = new byte[84];
        for (int d = 0; d < DAYS; d++) {
            for (int s = 0; s < SLOTS; s++) {
                TimeSlot ts = slots[d][s];
                int base = d * 12 + s * 4;
                if (ts.enabled) {
                    out[base]     = (byte) (ts.offMinute & 0x3F);
                    out[base + 1] = (byte) (ts.offHour    & 0x1F);
                    out[base + 2] = (byte) (ts.onMinute & 0x3F);
                    out[base + 3] = (byte) (0x80 | (ts.onHour & 0x7F));
                } else {
                    out[base] = out[base+1] = out[base+2] = out[base+3] = 0;
                }
            }
        }
        return out;
    }

    // ── Deserialise from 84-byte wire format (or shorter partial) ───────────
    
    /**
     * Deserializes a raw byte array into an S1Schedule object.
     * @param raw Raw 84-byte (or partial) array from the device.
     * @return A populated S1Schedule instance.
     */
    public static S1Schedule fromBytes(byte[] raw) {
        S1Schedule sched = new S1Schedule();
        if (raw == null) return sched;
        for (int d = 0; d < DAYS; d++) {
            for (int s = 0; s < SLOTS; s++) {
                int base = d * 12 + s * 4;
                if (base + 3 >= raw.length) return sched;
                int b0 = raw[base]     & 0xFF;
                int b1 = raw[base + 1] & 0xFF;
                int b2 = raw[base + 2] & 0xFF;
                int b3 = raw[base + 3] & 0xFF;
                TimeSlot ts = sched.slots[d][s];
                ts.enabled   = (b3 & 0x80) != 0;
                ts.offMinute = b0 & 0x3F;
                ts.offHour   = b1 & 0x1F;
                ts.onMinute  = b2 & 0x3F;
                ts.onHour    = b3 & 0x7F;
            }
        }
        return sched;
    }

    // ── TimeSlot ─────────────────────────────────────────────────────────────
    
    /**
     * Represents a single ON/OFF timer event in the schedule.
     */
    public static class TimeSlot {
        /** True if this timer slot is active. */
        public boolean enabled   = false;
        /** ON hour (0-23). */
        public int     onHour    = 7;
        /** ON minute (0-59). */
        public int     onMinute  = 0;
        /** OFF hour (0-23). */
        public int     offHour   = 8;
        /** OFF minute (0-59). */
        public int     offMinute = 0;

        /**
         * Returns a localized string of the ON time.
         * @param context Application context.
         * @return Formatted time string.
         */
        public String onTime(Context context) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, onHour);
            cal.set(java.util.Calendar.MINUTE, onMinute);
            return android.text.format.DateFormat.getTimeFormat(context).format(cal.getTime());
        }

        /**
         * Returns a localized string of the OFF time.
         * @param context Application context.
         * @return Formatted time string.
         */
        public String offTime(Context context) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, offHour);
            cal.set(java.util.Calendar.MINUTE, offMinute);
            return android.text.format.DateFormat.getTimeFormat(context).format(cal.getTime());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimeSlot timeSlot = (TimeSlot) o;
            return enabled == timeSlot.enabled &&
                    onHour == timeSlot.onHour &&
                    onMinute == timeSlot.onMinute &&
                    offHour == timeSlot.offHour &&
                    offMinute == timeSlot.offMinute;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(enabled, onHour, onMinute, offHour, offMinute);
        }

        /** 
         * Returns true if ON time equals OFF time (effectively zero duration).
         * @return True if a conflict exists.
         */
        public boolean isConflict() {
            return enabled && onHour == offHour && onMinute == offMinute;
        }
    }
}
