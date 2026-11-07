package app.phueber.senioralert;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Computes the next occurrence of hour:minute, i.e. when an alarm that is ON
 * will ring (once). Pure Java, unit tested.
 *
 * Calendar does the wall-clock arithmetic so daylight-saving changes come out
 * right: 07:00 stays 07:00 local time across a DST switch. A time equal to "now"
 * (same minute, any second) goes to tomorrow: switching an alarm ON at 07:00:30
 * for 07:00 must not ring instantly, and a re-arm (boot, time change) during the
 * very minute an alarm just rang must not ring it a second time.
 */
public final class NextTrigger {
    private NextTrigger() {}

    public static long next(int hour, int minute, long nowMillis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMillis) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            // Re-pin the wall-clock time: adding a day across a DST gap can shift it.
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
        }
        return c.getTimeInMillis();
    }
}
