package app.phueber.senioralert;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AlarmCodecTest {
    @Test public void roundTrip() {
        List<Alarm> in = Arrays.asList(new Alarm(1, 7, 30, true), new Alarm(2, 20, 0, false));
        String s = AlarmCodec.encode(in);
        assertEquals("1:7:30:1;2:20:0:0", s);
        assertEquals(in, AlarmCodec.decode(s));
    }

    @Test public void emptyAndNull() {
        assertEquals("", AlarmCodec.encode(new ArrayList<Alarm>()));
        assertTrue(AlarmCodec.decode("").isEmpty());
        assertTrue(AlarmCodec.decode(null).isEmpty());
    }

    @Test public void sortsByTimeThenId() {
        List<Alarm> out = AlarmCodec.decode("5:9:0:1;3:7:0:1;2:9:0:0;4:7:0:0;1:23:59:1");
        assertEquals(Arrays.asList(
                new Alarm(3, 7, 0, true), new Alarm(4, 7, 0, false),
                new Alarm(2, 9, 0, false), new Alarm(5, 9, 0, true),
                new Alarm(1, 23, 59, true)), out);
    }

    @Test public void malformedEntriesSkipped() {
        String s = String.join(";",
                "1:7:30:1",
                "2:7:30",        // too few fields
                "3:7:30:1:9",    // too many fields
                "x:7:30:1",      // non-number id
                "4:a:30:1",      // non-number hour
                "5:7:b:1",       // non-number minute
                "6:7:30:2",      // enabled not 0/1
                "7:7:30:true",   // enabled not 0/1
                "8:24:0:1",      // hour out of range
                "9:7:60:1",      // minute out of range
                "0:7:0:1",       // id out of range
                "",              // empty entry
                "10:8:0:0");
        assertEquals(Arrays.asList(new Alarm(1, 7, 30, true), new Alarm(10, 8, 0, false)),
                AlarmCodec.decode(s));
    }

    @Test public void garbageOnly() {
        assertTrue(AlarmCodec.decode("garbage").isEmpty());
        assertTrue(AlarmCodec.decode(";;;").isEmpty());
    }

    @Test public void duplicateIdsKeepFirst() {
        List<Alarm> out = AlarmCodec.decode("1:7:0:1;1:9:0:0;2:8:0:1");
        assertEquals(Arrays.asList(new Alarm(1, 7, 0, true), new Alarm(2, 8, 0, true)), out);
    }

    @Test public void roundTripEveryValidTimeAndFlag() {
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m++) {
                for (boolean en : new boolean[]{true, false}) {
                    Alarm a = new Alarm(1000000 + h * 60 + m, h, m, en);
                    List<Alarm> out = AlarmCodec.decode(AlarmCodec.encode(Arrays.asList(a)));
                    assertEquals(Arrays.asList(a), out);
                }
            }
        }
    }

    @Test public void roundTripManyAlarmsAfterSort() {
        java.util.Random r = new java.util.Random(42);
        for (int round = 0; round < 200; round++) {
            List<Alarm> in = new ArrayList<>();
            int n = r.nextInt(30);
            for (int i = 1; i <= n; i++) in.add(new Alarm(i, r.nextInt(24), r.nextInt(60), r.nextBoolean()));
            AlarmCodec.sort(in);
            List<Alarm> out = AlarmCodec.decode(AlarmCodec.encode(in));
            assertEquals(in, out);
            for (int i = 1; i < out.size(); i++) {
                Alarm p = out.get(i - 1), q = out.get(i);
                assertTrue(p.minuteOfDay() < q.minuteOfDay()
                        || (p.minuteOfDay() == q.minuteOfDay() && p.id < q.id));
            }
            // encode(decode(x)) is a fixed point.
            assertEquals(AlarmCodec.encode(in), AlarmCodec.encode(out));
        }
    }

    @Test public void encodeDoesNotReorderOrMutate() {
        List<Alarm> in = new ArrayList<>(Arrays.asList(new Alarm(2, 9, 0, true), new Alarm(1, 7, 0, true)));
        assertEquals("2:9:0:1;1:7:0:1", AlarmCodec.encode(in));
        assertEquals(2, in.get(0).id);
    }

    @Test public void sortIsStableForEqualKeysAndHandlesEmpty() {
        List<Alarm> empty = new ArrayList<>();
        AlarmCodec.sort(empty);
        assertTrue(empty.isEmpty());
        List<Alarm> l = new ArrayList<>(Arrays.asList(new Alarm(3, 1, 0, true), new Alarm(2, 1, 0, true)));
        AlarmCodec.sort(l);
        assertEquals(2, l.get(0).id);
    }

    @Test public void moreMalformedInputs() {
        String[] bad = {
            "1:7:30:", ":7:30:1", "1::30:1", "1:7::1", "1:7:30:01", "1:7:30:+1", "1: 7:30:1",
            "1:7:30:1 ", " 1:7:30:1", "1:07x:30:1", "1:7:30:1;", "99999999999:7:30:1",
            "-1:7:30:1", "1:-1:30:1", "1:7:-1:1", "1:7.0:30:1", "1:7:30:١",
            "::::", ":", "1;2;3", "1:7:30:1\n"
        };
        for (String s : bad) {
            List<Alarm> out = AlarmCodec.decode(s);
            // A trailing empty entry after ';' is ignored; nothing else here is valid.
            if (s.equals("1:7:30:1;")) assertEquals(s, 1, out.size());
            else assertTrue("should be rejected: [" + s + "] -> " + out, out.isEmpty());
        }
    }

    /** Quirk: String.split drops trailing empty fields, so "1:7:30:1:" decodes like "1:7:30:1". Harmless; encode never emits it. */
    @Test public void trailingColonIsTolerated() {
        assertEquals(Arrays.asList(new Alarm(1, 7, 30, true)), AlarmCodec.decode("1:7:30:1:"));
    }

    @Test public void oneCorruptEntryDoesNotHideOthers() {
        List<Alarm> out = AlarmCodec.decode("1:7:0:1;###;2:8:0:1;3:99:0:1;4:9:0:0");
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).id);
        assertEquals(2, out.get(1).id);
        assertEquals(4, out.get(2).id);
    }

    @Test public void duplicateIdKeepsFirstEvenIfLaterSortsEarlier() {
        List<Alarm> out = AlarmCodec.decode("1:20:0:1;1:6:0:1");
        assertEquals(Arrays.asList(new Alarm(1, 20, 0, true)), out);
    }

    @Test public void invalidDuplicateDoesNotShadowValidOne() {
        List<Alarm> out = AlarmCodec.decode("1:99:0:1;1:6:0:1");
        assertEquals(Arrays.asList(new Alarm(1, 6, 0, true)), out);
    }

    @Test public void decodedListIsMutable() {
        List<Alarm> out = AlarmCodec.decode("1:7:0:1");
        out.add(new Alarm(2, 8, 0, true));
        assertEquals(2, out.size());
    }
}
