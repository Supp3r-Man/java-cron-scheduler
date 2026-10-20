package com.github.sup3rmar10.cronscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Property-style test: {@link CronExpression#nextFire} against a brute-force scanner that walks UTC minutes one by
 * one, converts each to the zone's wall clock and checks the fields with its own independent matcher.
 *
 * <p>The oracle never parses text. The generator builds each field as text AND as the set of values that text is
 * meant to denote (from the documented grammar), so parser, day rule and next-fire search are all checked against
 * an independently computed truth. Seeds are fixed, so a failure reproduces; the message names the case.
 *
 * <p>The scanner only looks {@code windowMinutes} ahead (it is slow by design). If it finds a fire, nextFire must
 * return exactly that instant; if it finds none, nextFire must return nothing before the end of the window.
 */
class BruteForcePropertyTest {
    private static final List<ZoneId> ZONES = List.of(ZoneId.of("America/New_York"), ZoneId.of("Europe/London"),
            ZoneId.of("Australia/Lord_Howe"), ZoneId.of("Asia/Kolkata"));
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    // ------------------------------------------------------------------ generator

    private static final class Field {
        String text;
        final BitSet values = new BitSet();
        boolean wildcard;
    }

    private static final class Spec {
        String text;
        boolean hasSeconds;
        DayRule rule;
        BitSet sec;
        BitSet min;
        BitSet hour;
        BitSet dom;
        BitSet month;
        BitSet dow;
        boolean domWild;
        boolean dowWild;
    }

    private static final class Config {
        int wildMin = 35, wildHour = 40, wildDom = 80, wildMonth = 85, wildDow = 70, wildSec = 30;
        int hourCap = 23;
    }

    private static String tok(Random r, int v, String[] names, int base) {
        if (names != null && v - base >= 0 && v - base < names.length && r.nextInt(3) == 0) {
            String n = names[v - base];
            return r.nextBoolean() ? n : n.toLowerCase(Locale.ROOT);
        }
        return Integer.toString(v);
    }

    private static Field gen(Random r, int min, int max, int cap, int wildPct, String[] names, int nameBase, boolean dow,
                             boolean dayField) {
        Field f = new Field();
        int upperStar = dow ? 6 : max;
        int genMax = Math.min(cap, max);
        if (r.nextInt(100) < wildPct) {
            f.text = dayField && r.nextInt(4) == 0 ? "?" : "*";
            f.values.set(min, upperStar + 1);
            f.wildcard = true;
            return f;
        }
        int span = max - min + 1;
        int items = r.nextInt(3) == 0 ? 2 + r.nextInt(2) : 1;
        List<String> texts = new ArrayList<>();
        for (int k = 0; k < items; k++) {
            switch (r.nextInt(5)) {
                case 0 -> {
                    int v = min + r.nextInt(genMax - min + 1);
                    texts.add(tok(r, v, names, nameBase));
                    f.values.set(dow ? v % 7 : v);
                }
                case 1 -> {
                    int a = min + r.nextInt(genMax - min + 1);
                    int b = a + r.nextInt(genMax - a + 1);
                    texts.add(tok(r, a, names, nameBase) + "-" + tok(r, b, names, nameBase));
                    for (int v = a; v <= b; v++) {
                        f.values.set(dow ? v % 7 : v);
                    }
                }
                case 2 -> {
                    int n = 1 + r.nextInt(Math.min(span, 12));
                    texts.add("*/" + n);
                    for (int v = min; v <= upperStar; v += n) {
                        f.values.set(v);
                    }
                }
                case 3 -> {
                    int a = min + r.nextInt(genMax - min + 1);
                    int b = a + r.nextInt(genMax - a + 1);
                    int n = 1 + r.nextInt(Math.min(span, 12));
                    texts.add(a + "-" + b + "/" + n);
                    for (int v = a; v <= b; v += n) {
                        f.values.set(dow ? v % 7 : v);
                    }
                }
                default -> {
                    int a = min + r.nextInt(Math.min(genMax, upperStar) - min + 1);
                    int n = 1 + r.nextInt(Math.min(span, 12));
                    texts.add(a + "/" + n);
                    for (int v = a; v <= upperStar; v += n) {
                        f.values.set(v);
                    }
                }
            }
        }
        f.text = String.join(",", texts);
        return f;
    }

    private static Spec spec(Random r, Config c, boolean withSeconds) {
        Spec s = new Spec();
        s.hasSeconds = withSeconds;
        s.rule = r.nextBoolean() ? DayRule.OR : DayRule.AND;
        List<String> parts = new ArrayList<>();
        if (withSeconds) {
            Field f = gen(r, 0, 59, 59, c.wildSec, null, 0, false, false);
            parts.add(f.text);
            s.sec = f.values;
        } else {
            s.sec = new BitSet();
            s.sec.set(0);
        }
        Field mi = gen(r, 0, 59, 59, c.wildMin, null, 0, false, false);
        Field ho = gen(r, 0, 23, c.hourCap, c.wildHour, null, 0, false, false);
        Field dm = gen(r, 1, 31, 31, c.wildDom, null, 0, false, true);
        Field mo = gen(r, 1, 12, 12, c.wildMonth, MONTHS, 1, false, false);
        Field dw = gen(r, 0, 7, 7, c.wildDow, DAYS, 0, true, true);
        parts.add(mi.text);
        parts.add(ho.text);
        parts.add(dm.text);
        parts.add(mo.text);
        parts.add(dw.text);
        s.min = mi.values;
        s.hour = ho.values;
        s.dom = dm.values;
        s.month = mo.values;
        s.dow = dw.values;
        s.domWild = dm.wildcard;
        s.dowWild = dw.wildcard;
        s.text = String.join(" ", parts);
        return s;
    }

    // ------------------------------------------------------------------ instants

    private static List<Instant> transitions(ZoneId zone) {
        List<Instant> out = new ArrayList<>();
        ZoneRules rules = zone.getRules();
        Instant cursor = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2030-01-01T00:00:00Z");
        while (true) {
            ZoneOffsetTransition t = rules.nextTransition(cursor);
            if (t == null || t.getInstant().isAfter(end)) {
                return out;
            }
            out.add(t.getInstant());
            cursor = t.getInstant();
        }
    }

    private static Instant pick(Random r, ZoneId zone, List<Instant> transitions, boolean nearTransitionsOnly) {
        int k = nearTransitionsOnly ? 0 : r.nextInt(10);
        Instant base;
        if (k < 5 && !transitions.isEmpty()) {
            Instant t = transitions.get(r.nextInt(transitions.size()));
            base = t.plusSeconds(r.nextInt(2 * 72 * 3600) - 72 * 3600);
        } else if (k < 7) {
            int year = 2027 + r.nextInt(2);
            int month = 1 + r.nextInt(12);
            LocalDate last = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
            base = last.atTime(r.nextInt(24), r.nextInt(60), r.nextInt(60)).atZone(zone).toInstant()
                    .plusSeconds(r.nextInt(2 * 86400) - 86400);
        } else {
            long lo = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
            long hi = Instant.parse("2029-12-31T00:00:00Z").getEpochSecond();
            base = Instant.ofEpochSecond(lo + (long) (r.nextDouble() * (hi - lo)));
        }
        return r.nextInt(4) == 0 ? base.plusNanos(r.nextInt(1_000_000_000)) : base;
    }

    // ------------------------------------------------------------------ oracle

    private static Optional<Instant> scan(Spec s, ZoneId zone, Instant after, int windowMinutes) {
        long startMin = Math.floorDiv(after.getEpochSecond(), 60L);
        for (int k = 0; k < windowMinutes; k++) {
            long base = (startMin + k) * 60;
            ZonedDateTime z = Instant.ofEpochSecond(base).atZone(zone);
            if (!s.min.get(z.getMinute()) || !s.hour.get(z.getHour()) || !s.month.get(z.getMonthValue())) {
                continue;
            }
            boolean domIn = s.dom.get(z.getDayOfMonth());
            boolean dowIn = s.dow.get(z.getDayOfWeek().getValue() % 7);
            boolean dayOk = (s.rule == DayRule.AND || s.domWild || s.dowWild) ? (domIn && dowIn) : (domIn || dowIn);
            if (!dayOk) {
                continue;
            }
            for (int sec = s.sec.nextSetBit(0); sec >= 0; sec = s.sec.nextSetBit(sec + 1)) {
                Instant c = Instant.ofEpochSecond(base + sec);
                if (c.isAfter(after)) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    /** Runs {@code cases} random cases; returns how many had a fire inside the scan window. */
    private static int run(long seed, int cases, Config cfg, boolean withSeconds, int windowMinutes, boolean transitionsOnly) {
        Random r = new Random(seed);
        List<List<Instant>> trans = new ArrayList<>();
        for (ZoneId z : ZONES) {
            trans.add(transitions(z));
        }
        int found = 0;
        for (int n = 0; n < cases; n++) {
            Spec s = spec(r, cfg, withSeconds);
            int zi = r.nextInt(ZONES.size());
            ZoneId zone = ZONES.get(zi);
            Instant after = pick(r, zone, trans.get(zi), transitionsOnly);
            String label = String.format("case %d seed=%d expr='%s' rule=%s zone=%s after=%s", n, seed, s.text, s.rule, zone, after);

            CronExpression e = CronExpression.parse(s.text, s.rule);
            Optional<ZonedDateTime> actual = e.nextFire(after, zone);
            Optional<Instant> expected = scan(s, zone, after, windowMinutes);
            if (expected.isPresent()) {
                found++;
                assertTrue(actual.isPresent(), label + " expected " + expected.get() + " but got none");
                assertEquals(expected.get(), actual.get().toInstant(), label);
                assertEquals(zone, actual.get().getZone(), label);
            } else {
                Instant windowEnd = Instant.ofEpochSecond((Math.floorDiv(after.getEpochSecond(), 60L) + windowMinutes) * 60);
                assertTrue(actual.isEmpty() || !actual.get().toInstant().isBefore(windowEnd),
                        label + " scanner saw nothing before " + windowEnd + " but nextFire returned "
                                + actual.map(Object::toString).orElse("none"));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Timeout(60)
    void fiveFieldExpressionsMatchTheBruteForceScanner() {
        int cases = 2400;
        int found = run(0xC0FFEEL, cases, new Config(), false, 2 * 24 * 60, false);
        assertTrue(found > cases * 0.7, "too few cases exercised a real match: " + found + "/" + cases);
    }

    @Test
    @Timeout(60)
    void sixFieldExpressionsMatchTheBruteForceScanner() {
        int cases = 1200;
        int found = run(0xBADCAFEL, cases, new Config(), true, 2 * 24 * 60, false);
        assertTrue(found > cases * 0.6, "too few cases exercised a real match: " + found + "/" + cases);
    }

    @Test
    @Timeout(60)
    void sparseDateExpressionsMatchTheBruteForceScanner() {
        Config c = new Config();
        c.wildMin = 10;
        c.wildHour = 10;
        c.wildDom = 25;
        c.wildMonth = 60;
        c.wildDow = 60;
        int cases = 250;
        int found = run(0x5EEDL, cases, c, false, 45 * 24 * 60, false);
        assertTrue(found > cases * 0.4, "too few cases exercised a real match: " + found + "/" + cases);
    }

    @Test
    @Timeout(60)
    void expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner() {
        Config c = new Config();
        c.hourCap = 4;
        c.wildHour = 0;
        c.wildDom = 90;
        c.wildMonth = 90;
        c.wildDow = 90;
        int cases = 1500;
        int found = run(0xD57L, cases, c, false, 3 * 24 * 60, true);
        assertTrue(found > cases * 0.75, "too few cases exercised a real match: " + found + "/" + cases);
    }

    @Test
    @Timeout(60)
    void dstFocusedSixFieldExpressionsMatchTheBruteForceScanner() {
        Config c = new Config();
        c.hourCap = 4;
        c.wildHour = 0;
        c.wildDom = 90;
        c.wildMonth = 90;
        c.wildDow = 90;
        int cases = 600;
        int found = run(0xD58L, cases, c, true, 3 * 24 * 60, true);
        assertTrue(found > cases * 0.75, "too few cases exercised a real match: " + found + "/" + cases);
    }

    @Test
    void theScannerItselfAgreesWithAHandComputedCase() {
        // 30 1 * * * in New York across the fall-back day: the scanner must also see 01:30 twice.
        Spec s = new Spec();
        s.rule = DayRule.OR;
        s.sec = new BitSet();
        s.sec.set(0);
        s.min = new BitSet();
        s.min.set(30);
        s.hour = new BitSet();
        s.hour.set(1);
        s.dom = new BitSet();
        s.dom.set(1, 32);
        s.dow = new BitSet();
        s.dow.set(0, 7);
        s.month = new BitSet();
        s.month.set(1, 13);
        s.domWild = true;
        s.dowWild = true;
        ZoneId ny = ZoneId.of("America/New_York");
        Instant t = Instant.parse("2026-11-01T04:00:00Z");
        Instant a = scan(s, ny, t, 24 * 60).orElseThrow();
        Instant b = scan(s, ny, a, 24 * 60).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), a);
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), b);
    }
}
