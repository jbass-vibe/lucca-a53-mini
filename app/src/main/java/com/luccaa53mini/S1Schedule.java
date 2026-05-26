package com.luccaa53mini;

import android.content.Context;

/**
 * Represents the complete 7-day boiler schedule.
 *
 * Wire format: 84 bytes = 7 days × 12 bytes = 7 days × 3 slots × 4 bytes.
 * Each 4-byte slot: [on_min][on_hour][off_min][flags_off_hour]
 *   flags_off_hour: bit7 = enabled (1=slot active), bits6-0 = off_hour
 */
public class S1Schedule {

    public static final int DAYS  = 7;
    public static final int SLOTS = 3;

    /** [day 0-6][slot 0-2] */
    public final TimeSlot[][] slots = new TimeSlot[DAYS][SLOTS];

    public static final String[] DAY_NAMES =
            {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    public static final String[] DAY_SHORT =
            {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    public S1Schedule() {
        for (int d = 0; d < DAYS; d++)
            for (int s = 0; s < SLOTS; s++)
                slots[d][s] = new TimeSlot();
    }

    // ── Serialise to 84-byte wire format ────────────────────────────────────
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
    public static class TimeSlot {
        public boolean enabled   = false;
        public int     onHour    = 7;
        public int     onMinute  = 0;
        public int     offHour   = 8;
        public int     offMinute = 0;

        public String onTime(Context context) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, onHour);
            cal.set(java.util.Calendar.MINUTE, onMinute);
            return android.text.format.DateFormat.getTimeFormat(context).format(cal.getTime());
        }

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

        /** Returns true if on-time == off-time (invalid slot) */
        public boolean isConflict() {
            return enabled && onHour == offHour && onMinute == offMinute;
        }
    }
}
