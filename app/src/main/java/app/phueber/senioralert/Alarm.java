package app.phueber.senioralert;

/**
 * One alarm. Immutable; pure Java so it can be unit tested on the JVM.
 * One-shot, no repeat: when ON it rings once, at the next hour:minute (local
 * wall-clock time), and then switches itself OFF. It rings again only after
 * someone switches it ON again.
 */
public final class Alarm {
    public final int id;
    public final int hour;    // 0..23
    public final int minute;  // 0..59
    public final boolean enabled;

    public Alarm(int id, int hour, int minute, boolean enabled) {
        if (id <= 0) throw new IllegalArgumentException("id must be > 0: " + id);
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour: " + hour);
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("minute: " + minute);
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.enabled = enabled;
    }

    public Alarm withEnabled(boolean e) {
        return new Alarm(id, hour, minute, e);
    }

    public Alarm withTime(int h, int m) {
        return new Alarm(id, h, m, enabled);
    }

    /** Minutes since midnight; used to sort the list by time of day. */
    public int minuteOfDay() {
        return hour * 60 + minute;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Alarm)) return false;
        Alarm a = (Alarm) o;
        return id == a.id && hour == a.hour && minute == a.minute && enabled == a.enabled;
    }

    @Override
    public int hashCode() {
        return ((id * 31 + hour) * 31 + minute) * 2 + (enabled ? 1 : 0);
    }

    @Override
    public String toString() {
        return "Alarm{" + id + " " + hour + ":" + minute + (enabled ? " on" : " off") + "}";
    }
}
