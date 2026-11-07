package app.phueber.senioralert;

import static org.junit.Assert.*;

import org.junit.Test;

public class AlarmTest {
    @Test public void validConstruction() {
        Alarm a = new Alarm(1, 7, 30, true);
        assertEquals(1, a.id);
        assertEquals(7, a.hour);
        assertEquals(30, a.minute);
        assertTrue(a.enabled);
        assertEquals(450, a.minuteOfDay());
    }

    @Test public void boundsAccepted() {
        new Alarm(1, 0, 0, false);
        new Alarm(Integer.MAX_VALUE, 23, 59, true);
    }

    @Test(expected = IllegalArgumentException.class) public void idZero() { new Alarm(0, 7, 0, true); }
    @Test(expected = IllegalArgumentException.class) public void idNegative() { new Alarm(-1, 7, 0, true); }
    @Test(expected = IllegalArgumentException.class) public void hourNegative() { new Alarm(1, -1, 0, true); }
    @Test(expected = IllegalArgumentException.class) public void hour24() { new Alarm(1, 24, 0, true); }
    @Test(expected = IllegalArgumentException.class) public void minuteNegative() { new Alarm(1, 7, -1, true); }
    @Test(expected = IllegalArgumentException.class) public void minute60() { new Alarm(1, 7, 60, true); }

    @Test public void everyValidTimeConstructsAndMinuteOfDayIsUniqueAndOrdered() {
        int prev = -1;
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m++) {
                Alarm a = new Alarm(1, h, m, true);
                assertEquals(h * 60 + m, a.minuteOfDay());
                assertTrue(a.minuteOfDay() > prev);
                prev = a.minuteOfDay();
            }
        }
        assertEquals(1439, prev);
    }

    @Test public void extremeInvalidValues() {
        int[] bad = {Integer.MIN_VALUE, -1, 24, 60, Integer.MAX_VALUE};
        for (int v : bad) {
            try { new Alarm(1, v, 0, true); if (v < 0 || v > 23) fail("hour " + v); }
            catch (IllegalArgumentException expected) { }
            try { new Alarm(1, 0, v, true); if (v < 0 || v > 59) fail("minute " + v); }
            catch (IllegalArgumentException expected) { }
        }
        try { new Alarm(Integer.MIN_VALUE, 0, 0, true); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void withTimeInvalidLeavesOriginalUsable() {
        Alarm a = new Alarm(1, 7, 0, true);
        try { a.withTime(7, 60); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(new Alarm(1, 7, 0, true), a);
    }

    @Test public void equalObjectsHaveEqualHashCodes() {
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 5) {
                assertEquals(new Alarm(9, h, m, false).hashCode(), new Alarm(9, h, m, false).hashCode());
            }
        }
    }

    @Test public void withEnabledKeepsRest() {
        Alarm a = new Alarm(3, 8, 15, true).withEnabled(false);
        assertEquals(new Alarm(3, 8, 15, false), a);
    }

    @Test public void withTimeKeepsRest() {
        Alarm a = new Alarm(3, 8, 15, false).withTime(21, 5);
        assertEquals(new Alarm(3, 21, 5, false), a);
    }

    @Test(expected = IllegalArgumentException.class) public void withTimeValidates() {
        new Alarm(3, 8, 15, true).withTime(25, 0);
    }

    @Test public void withDoesNotMutate() {
        Alarm a = new Alarm(3, 8, 15, true);
        a.withEnabled(false);
        a.withTime(1, 1);
        assertEquals(new Alarm(3, 8, 15, true), a);
    }

    @Test public void equalsAndHashCode() {
        Alarm a = new Alarm(1, 7, 0, true);
        assertEquals(a, new Alarm(1, 7, 0, true));
        assertEquals(a.hashCode(), new Alarm(1, 7, 0, true).hashCode());
        assertNotEquals(a, new Alarm(2, 7, 0, true));
        assertNotEquals(a, new Alarm(1, 8, 0, true));
        assertNotEquals(a, new Alarm(1, 7, 1, true));
        assertNotEquals(a, new Alarm(1, 7, 0, false));
        assertNotEquals(a, null);
        assertNotEquals(a, "1:7:0:1");
    }
}
