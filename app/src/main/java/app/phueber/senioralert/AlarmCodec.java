package app.phueber.senioralert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serialises the alarm list to one SharedPreferences string, so the app needs no
 * database and no JSON library. Format: "id:hour:minute:enabled" joined by ';',
 * e.g. "1:7:30:1;2:20:0:0". Pure Java, unit tested.
 *
 * decode() skips malformed entries instead of throwing: one corrupt entry must
 * never make every other alarm disappear.
 */
public final class AlarmCodec {
    private AlarmCodec() {}

    public static String encode(List<Alarm> alarms) {
        StringBuilder sb = new StringBuilder();
        for (Alarm a : alarms) {
            if (sb.length() > 0) sb.append(';');
            sb.append(a.id).append(':').append(a.hour).append(':').append(a.minute)
              .append(':').append(a.enabled ? '1' : '0');
        }
        return sb.toString();
    }

    /** Decodes and returns the alarms sorted by time of day (then id). */
    public static List<Alarm> decode(String s) {
        List<Alarm> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(":");
            if (f.length != 4) continue;
            try {
                int id = Integer.parseInt(f[0]);
                int h = Integer.parseInt(f[1]);
                int m = Integer.parseInt(f[2]);
                boolean en;
                if (f[3].equals("1")) en = true;
                else if (f[3].equals("0")) en = false;
                else continue;
                Alarm a = new Alarm(id, h, m, en);
                boolean dup = false;
                for (Alarm b : out) if (b.id == a.id) { dup = true; break; }
                if (!dup) out.add(a);
            } catch (IllegalArgumentException e) {
                // NumberFormatException is an IllegalArgumentException too.
            }
        }
        sort(out);
        return out;
    }

    public static void sort(List<Alarm> alarms) {
        Collections.sort(alarms, (a, b) -> a.minuteOfDay() != b.minuteOfDay()
                ? Integer.compare(a.minuteOfDay(), b.minuteOfDay())
                : Integer.compare(a.id, b.id));
    }
}
