package app.phueber.senioralert;

import static org.junit.Assert.*;

import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;

public class NextTriggerTest {
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    private static Calendar cal(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c;
    }

    private static void assertLocal(long t, TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = cal(t, tz);
        assertEquals("year", y, c.get(Calendar.YEAR));
        assertEquals("month", mo, c.get(Calendar.MONTH) + 1);
        assertEquals("day", d, c.get(Calendar.DAY_OF_MONTH));
        assertEquals("hour", h, c.get(Calendar.HOUR_OF_DAY));
        assertEquals("minute", mi, c.get(Calendar.MINUTE));
        assertEquals("second", 0, c.get(Calendar.SECOND));
        assertEquals("ms", 0, c.get(Calendar.MILLISECOND));
    }

    @Test public void laterToday() {
        long now = at(UTC, 2026, 6, 10, 6, 0, 0, 0);
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2026, 6, 10, 7, 0);
    }

    @Test public void earlierTodayGoesToTomorrow() {
        long now = at(UTC, 2026, 6, 10, 8, 0, 0, 0);
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2026, 6, 11, 7, 0);
    }

    @Test public void sameMinuteLaterSecondGoesToTomorrow() {
        long now = at(UTC, 2026, 6, 10, 7, 0, 30, 0);
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2026, 6, 11, 7, 0);
    }

    @Test public void exactlyNowGoesToTomorrow() {
        long now = at(UTC, 2026, 6, 10, 7, 0, 0, 0);
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2026, 6, 11, 7, 0);
    }

    @Test public void oneMillisecondBeforeFiresToday() {
        long now = at(UTC, 2026, 6, 10, 7, 0, 0, 0) - 1;
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2026, 6, 10, 7, 0);
    }

    @Test public void midnight() {
        long now = at(UTC, 2026, 6, 10, 12, 0, 0, 0);
        assertLocal(NextTrigger.next(0, 0, now, UTC), UTC, 2026, 6, 11, 0, 0);
        long justBefore = at(UTC, 2026, 6, 10, 23, 59, 59, 999);
        assertLocal(NextTrigger.next(0, 0, justBefore, UTC), UTC, 2026, 6, 11, 0, 0);
    }

    @Test public void lastMinuteOfDay() {
        long now = at(UTC, 2026, 6, 10, 12, 0, 0, 0);
        assertLocal(NextTrigger.next(23, 59, now, UTC), UTC, 2026, 6, 10, 23, 59);
        long at2359 = at(UTC, 2026, 6, 10, 23, 59, 10, 0);
        assertLocal(NextTrigger.next(23, 59, at2359, UTC), UTC, 2026, 6, 11, 23, 59);
    }

    @Test public void monthAndYearRollover() {
        long now = at(UTC, 2026, 12, 31, 8, 0, 0, 0);
        assertLocal(NextTrigger.next(7, 0, now, UTC), UTC, 2027, 1, 1, 7, 0);
    }

    @Test public void usesGivenTimeZoneNotDefault() {
        // 2026-06-10 05:30 UTC = 07:30 in Berlin (CEST); 07:00 Berlin is already past.
        long now = at(UTC, 2026, 6, 10, 5, 30, 0, 0);
        assertLocal(NextTrigger.next(7, 0, now, BERLIN), BERLIN, 2026, 6, 11, 7, 0);
    }

    @Test public void dstSpringForwardSevenAm() {
        // Clocks jump 02:00 -> 03:00 on 2026-03-29. 21:00 -> 07:00 is 10 local
        // hours but only 9 real hours.
        long now = at(BERLIN, 2026, 3, 28, 21, 0, 0, 0);
        long t = NextTrigger.next(7, 0, now, BERLIN);
        assertLocal(t, BERLIN, 2026, 3, 29, 7, 0);
        assertEquals(9 * 3600_000L, t - now);
    }

    @Test public void dstSpringForwardAfterFiring() {
        // Alarm 07:00 just fired on the 28th; the next is 07:00 on the 29th, 23h later.
        long now = at(BERLIN, 2026, 3, 28, 7, 0, 5, 0);
        long t = NextTrigger.next(7, 0, now, BERLIN);
        assertLocal(t, BERLIN, 2026, 3, 29, 7, 0);
        assertEquals(23 * 3600_000L - 5_000L, t - now);
    }

    @Test public void dstSpringForwardNonexistentTime() {
        // 02:30 does not exist on 2026-03-29 in Berlin. Calendar is lenient and
        // resolves it by advancing past the gap (03:30 CEST). We only require:
        // still the 29th, between 03:00 and 04:00 local.
        long now = at(BERLIN, 2026, 3, 28, 21, 0, 0, 0);
        long t = NextTrigger.next(2, 30, now, BERLIN);
        Calendar c = cal(t, BERLIN);
        assertEquals(2026, c.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, c.get(Calendar.MONTH));
        assertEquals(29, c.get(Calendar.DAY_OF_MONTH));
        long from = at(BERLIN, 2026, 3, 29, 3, 0, 0, 0);
        long to = at(BERLIN, 2026, 3, 29, 4, 0, 0, 0);
        assertTrue("expected [03:00,04:00) but was " + c.getTime(), t >= from && t < to);
    }

    @Test public void dstFallBackSevenAm() {
        // Clocks go 03:00 -> 02:00 on 2026-10-25; the day has 25 hours, so
        // 21:00 -> 07:00 is 11 real hours.
        long now = at(BERLIN, 2026, 10, 24, 21, 0, 0, 0);
        long t = NextTrigger.next(7, 0, now, BERLIN);
        assertLocal(t, BERLIN, 2026, 10, 25, 7, 0);
        assertEquals(11 * 3600_000L, t - now);
    }

    @Test public void dstFallBackNextDayAfterFiring() {
        long now = at(BERLIN, 2026, 10, 25, 7, 0, 1, 0);
        assertLocal(NextTrigger.next(7, 0, now, BERLIN), BERLIN, 2026, 10, 26, 7, 0);
    }

    private static final String[] ZONES = {
        "UTC", "Europe/Berlin", "Europe/London", "America/New_York", "America/Sao_Paulo",
        "Asia/Kolkata", "Asia/Kathmandu", "Australia/Sydney", "Australia/Lord_Howe",
        "Pacific/Auckland", "Pacific/Kiritimati", "Pacific/Apia"
    };

    /** Sweeps 'now' across 2026 DST changes in many zones and checks all invariants. */
    @Test public void invariantsAcrossZonesAndDstDays() {
        int[][] times = {{0, 0}, {0, 1}, {1, 59}, {2, 0}, {2, 30}, {3, 0}, {7, 0}, {12, 34},
                {23, 0}, {23, 59}};
        // Start of windows around spring/autumn changes in both hemispheres.
        int[][] windows = {{2026, 3, 26}, {2026, 4, 3}, {2026, 9, 25}, {2026, 10, 3},
                {2026, 10, 22}, {2026, 11, 1}, {2026, 1, 1}};
        for (String id : ZONES) {
            TimeZone tz = TimeZone.getTimeZone(id);
            for (int[] w : windows) {
                long start = at(tz, w[0], w[1], w[2], 0, 0, 0, 0);
                for (int step = 0; step < 6 * 24 * 2; step++) {   // 6 days, every 30 min
                    long now = start + step * 30 * 60_000L + (step % 7) * 8_123L;
                    for (int[] hm : times) {
                        long t = NextTrigger.next(hm[0], hm[1], now, tz);
                        String ctx = id + " now=" + cal(now, tz).getTime() + " " + hm[0] + ":" + hm[1];
                        assertTrue("not in future: " + ctx, t > now);
                        assertTrue("more than 25h: " + ctx, t - now <= 25 * 3600_000L);
                        assertEquals("seconds: " + ctx, 0, t % 60_000L);
                        Calendar c = cal(t, tz);
                        int wanted = hm[0] * 60 + hm[1];
                        int got = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                        if (got != wanted) {
                            // Only legal inside a DST gap: lenient Calendar moves forward by <= 60 min.
                            int diff = ((got - wanted) + 1440) % 1440;
                            assertTrue("wall-clock drift: " + ctx + " got " + c.getTime(),
                                    diff > 0 && diff <= 60);
                        }
                    }
                }
            }
        }
    }

    /** The next fire time is the earliest one: asking again just after it moves a day on. */
    @Test public void consecutiveTriggersAreAboutADayApart() {
        for (String id : ZONES) {
            TimeZone tz = TimeZone.getTimeZone(id);
            long now = at(tz, 2026, 3, 20, 12, 0, 0, 0);
            long prev = NextTrigger.next(7, 0, now, tz);
            for (int i = 0; i < 400; i++) {
                long t = NextTrigger.next(7, 0, prev, tz);   // rescheduling from the receiver
                long d = t - prev;
                assertTrue(id + " " + cal(prev, tz).getTime(), d >= 23 * 3600_000L && d <= 25 * 3600_000L);
                Calendar c = cal(t, tz);
                assertEquals(id, 7, c.get(Calendar.HOUR_OF_DAY));
                assertEquals(id, 0, c.get(Calendar.MINUTE));
                prev = t;
            }
        }
    }

    @Test public void everyHourAndMinuteEveryMinuteOfADayInUtc() {
        long start = at(UTC, 2026, 6, 10, 0, 0, 0, 0);
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 7) {
                long wanted = start + (h * 60 + m) * 60_000L;
                for (int s = 0; s < 24 * 60; s += 11) {
                    long now = start + s * 60_000L + 500;
                    long t = NextTrigger.next(h, m, now, UTC);
                    long expected = wanted > now ? wanted : wanted + 24 * 3600_000L;
                    assertEquals(h + ":" + m + " now+" + s + "min", expected, t);
                }
            }
        }
    }

    @Test public void resultIsAlwaysInTheFutureAndWithinOneDay() {
        long start = at(BERLIN, 2026, 3, 27, 0, 0, 0, 0);
        for (int step = 0; step < 5 * 24 * 4; step++) {
            long now = start + step * 15 * 60_000L + 17_000L;
            for (int h : new int[]{0, 2, 3, 7, 23}) {
                long t = NextTrigger.next(h, 0, now, BERLIN);
                assertTrue(t > now);
                assertTrue(t - now <= 25 * 3600_000L);
            }
        }
    }
}
