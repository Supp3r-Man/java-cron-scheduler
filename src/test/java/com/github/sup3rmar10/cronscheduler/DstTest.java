package com.github.sup3rmar10.cronscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the DST policy (literal wall clock): a local time inside a spring-forward gap is skipped, a local time inside
 * a fall-back overlap fires once per pass. 2026 transitions used here:
 * New York 8 Mar (02:00 EST to 03:00 EDT) and 1 Nov (02:00 EDT to 01:00 EST); London 29 Mar (01:00 GMT to 02:00 BST)
 * and 25 Oct (02:00 BST to 01:00 GMT); Lord Howe 4 Oct (02:00 +10:30 to 02:30 +11:00) and 5 Apr (02:00 +11:00 to 01:30 +10:30).
 */
class DstTest {
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId LORD_HOWE = ZoneId.of("Australia/Lord_Howe");

    private static ZonedDateTime z(String s) {
        return ZonedDateTime.parse(s);
    }

    private static String next(String expr, ZonedDateTime after) {
        return CronExpression.parse(expr).nextFire(after).map(ZonedDateTime::toString).orElse("none");
    }

    private static List<String> nextN(String expr, ZonedDateTime after, int n) {
        List<String> out = new ArrayList<>();
        for (ZonedDateTime t : CronExpression.parse(expr).nextFires(after.toInstant(), after.getZone(), n)) {
            out.add(t.toString());
        }
        return out;
    }

    /** Number of fires with local date-time in [dayStart, dayStart + 1 local day). */
    private static int firesOnLocalDay(String expr, ZonedDateTime dayStart) {
        ZonedDateTime end = dayStart.toLocalDate().plusDays(1).atStartOfDay(dayStart.getZone());
        CronExpression e = CronExpression.parse(expr);
        int count = 0;
        Optional<ZonedDateTime> n = e.nextFire(dayStart.toInstant().minusSeconds(1), dayStart.getZone());
        while (n.isPresent() && n.get().isBefore(end)) {
            count++;
            n = e.nextFire(n.get());
        }
        return count;
    }

    // ---------------------------------------------------------------- New York

    @Test
    void newYorkSpringForwardSkipsTimesInsideTheGap() {
        // 02:30 does not exist on 8 March, so that day has no run: Mar 7 02:30 EST, then Mar 9 02:30 EDT.
        assertEquals("2026-03-09T02:30-04:00[America/New_York]", next("30 2 * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
        assertEquals("2026-03-07T02:30-05:00[America/New_York]", next("30 2 * * *", z("2026-03-07T02:00-05:00[America/New_York]")));
    }

    @Test
    void newYorkTimesJustOutsideTheGapStillFire() {
        assertEquals("2026-03-08T01:59-05:00[America/New_York]", next("59 1 * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
        assertEquals("2026-03-08T03:00-04:00[America/New_York]", next("0 3 * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
    }

    @Test
    void newYorkFallBackFiresARepeatedWallClockTimeTwice() {
        assertEquals(List.of("2026-11-01T01:30-04:00[America/New_York]", "2026-11-01T01:30-05:00[America/New_York]",
                        "2026-11-02T01:30-05:00[America/New_York]"),
                nextN("30 1 * * *", z("2026-11-01T00:00-04:00[America/New_York]"), 3));
    }

    @Test
    void newYorkFallBackDoesNotRepeatTimesOutsideTheOverlap() {
        assertEquals(List.of("2026-11-01T02:00-05:00[America/New_York]", "2026-11-02T02:00-05:00[America/New_York]"),
                nextN("0 2 * * *", z("2026-11-01T00:00-04:00[America/New_York]"), 2));
    }

    @Test
    void searchingFromInsideTheSecondPassOfTheOverlap() {
        // 01:45 EDT is the first pass; the next */30 slot is 01:00 EST, i.e. only 15 minutes later in elapsed time.
        ZonedDateTime firstPass = z("2026-11-01T01:45-04:00[America/New_York]");
        ZonedDateTime n = CronExpression.parse("*/30 * * * *").nextFire(firstPass).orElseThrow();
        assertEquals("2026-11-01T01:00-05:00[America/New_York]", n.toString());
        assertEquals(Duration.ofMinutes(15), Duration.between(firstPass, n));
        // From inside the second pass (01:40 EST) the 01:30 EST slot is already past, so 02:00 EST is next.
        assertEquals("2026-11-01T02:00-05:00[America/New_York]",
                next("*/30 * * * *", z("2026-11-01T01:40-05:00[America/New_York]")));
    }

    @Test
    void hourlyExpressionHasTwentyThreeRunsOnSpringForwardDayAndTwentyFiveOnFallBackDay() {
        assertEquals(23, firesOnLocalDay("0 * * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
        assertEquals(25, firesOnLocalDay("0 * * * *", z("2026-11-01T00:00-04:00[America/New_York]")));
        assertEquals(24, firesOnLocalDay("0 * * * *", z("2026-06-01T00:00-04:00[America/New_York]")));
    }

    @Test
    void quarterHourlyCountsMatchTheLengthOfTheLocalDay() {
        assertEquals(92, firesOnLocalDay("*/15 * * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
        assertEquals(100, firesOnLocalDay("*/15 * * * *", z("2026-11-01T00:00-04:00[America/New_York]")));
    }

    @Test
    void aDailyJobOutsideTheChangeWindowRunsOncePerDayEvenOnChangeDays() {
        assertEquals(1, firesOnLocalDay("0 12 * * *", z("2026-03-08T00:00-05:00[America/New_York]")));
        assertEquals(1, firesOnLocalDay("0 12 * * *", z("2026-11-01T00:00-04:00[America/New_York]")));
    }

    @Test
    void wallClockStaysFixedAcrossTheChange() {
        // 09:00 local is 14:00Z before the change and 13:00Z after it.
        List<ZonedDateTime> l = CronExpression.parse("0 9 * * *")
                .nextFires(z("2026-03-07T00:00-05:00[America/New_York]").toInstant(), NY, 3);
        assertEquals(Instant.parse("2026-03-07T14:00:00Z"), l.get(0).toInstant());
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), l.get(1).toInstant());
        assertEquals(Instant.parse("2026-03-09T13:00:00Z"), l.get(2).toInstant());
    }

    // ---------------------------------------------------------------- London

    @Test
    void londonSpringForwardSkipsOneOClockHour() {
        assertEquals(List.of("2026-03-28T01:30Z[Europe/London]", "2026-03-30T01:30+01:00[Europe/London]"),
                nextN("30 1 * * *", z("2026-03-28T00:00Z[Europe/London]"), 2));
    }

    @Test
    void londonFallBackFiresOneThirtyTwice() {
        assertEquals(List.of("2026-10-25T01:30+01:00[Europe/London]", "2026-10-25T01:30Z[Europe/London]",
                        "2026-10-26T01:30Z[Europe/London]"),
                nextN("30 1 * * *", z("2026-10-25T00:00+01:00[Europe/London]"), 3));
    }

    @Test
    void londonSecondsFieldInsideTheGapIsSkippedToo() {
        // 01:00:30 on 29 March does not exist.
        assertEquals("2026-03-30T01:00:30+01:00[Europe/London]", next("30 0 1 * * *", z("2026-03-29T00:00Z[Europe/London]")));
    }

    // ---------------------------------------------------------------- Lord Howe (30 minute shift)

    @Test
    void lordHoweSpringForwardGapIsOnlyThirtyMinutesWide() {
        // 02:00-02:29 does not exist on 4 Oct; 01:50 is followed by 02:30 (+11:00), only 10 minutes later.
        ZonedDateTime after = z("2026-10-04T01:40+10:30[Australia/Lord_Howe]");
        List<ZonedDateTime> l = CronExpression.parse("*/10 * * * *").nextFires(after.toInstant(), LORD_HOWE, 3);
        assertEquals("2026-10-04T01:50+10:30[Australia/Lord_Howe]", l.get(0).toString());
        assertEquals("2026-10-04T02:30+11:00[Australia/Lord_Howe]", l.get(1).toString());
        assertEquals("2026-10-04T02:40+11:00[Australia/Lord_Howe]", l.get(2).toString());
        assertEquals(Duration.ofMinutes(10), Duration.between(l.get(0), l.get(1)));
    }

    @Test
    void lordHoweDailyJobInsideTheGapIsSkippedAndOneOutsideIsNot() {
        assertEquals("2026-10-05T02:15+11:00[Australia/Lord_Howe]", next("15 2 * * *", z("2026-10-04T00:00+10:30[Australia/Lord_Howe]")));
        assertEquals("2026-10-04T02:45+11:00[Australia/Lord_Howe]", next("45 2 * * *", z("2026-10-04T00:00+10:30[Australia/Lord_Howe]")));
    }

    @Test
    void lordHoweFallBackRepeatsThirtyMinutes() {
        // 01:30-01:59 happens twice on 5 April: +11:00 first, then +10:30.
        assertEquals(List.of("2026-04-05T01:45+11:00[Australia/Lord_Howe]", "2026-04-05T01:45+10:30[Australia/Lord_Howe]",
                        "2026-04-06T01:45+10:30[Australia/Lord_Howe]"),
                nextN("45 1 * * *", z("2026-04-05T00:00+11:00[Australia/Lord_Howe]"), 3));
        ZonedDateTime first = z("2026-04-05T01:45+11:00[Australia/Lord_Howe]");
        ZonedDateTime n = CronExpression.parse("*/15 * * * *").nextFire(first).orElseThrow();
        assertEquals("2026-04-05T01:30+10:30[Australia/Lord_Howe]", n.toString());
        assertEquals(Duration.ofMinutes(15), Duration.between(first, n));
    }

    @Test
    void lordHoweDayLengthsInRuns() {
        assertEquals(47, firesOnLocalDay("*/30 * * * *", z("2026-10-04T00:00+10:30[Australia/Lord_Howe]")));
        assertEquals(49, firesOnLocalDay("*/30 * * * *", z("2026-04-05T00:00+11:00[Australia/Lord_Howe]")));
    }

    // ---------------------------------------------------------------- general

    @Test
    void resultsAreAlwaysStrictlyIncreasingInInstantTimeAcrossTransitions() {
        for (ZoneId zone : List.of(NY, LONDON, LORD_HOWE)) {
            Instant cursor = Instant.parse("2026-01-01T00:00:00Z");
            CronExpression e = CronExpression.parse("*/20 * * * *");
            for (int k = 0; k < 20000; k++) {
                Instant n = e.nextFire(cursor, zone).orElseThrow().toInstant();
                assertTrue(n.isAfter(cursor), zone + " " + cursor + " -> " + n);
                cursor = n;
            }
        }
    }

    @Test
    void aWeeklySundayJobLosesOnlyTheGapWeek() {
        assertEquals(List.of("2026-03-01T02:30-05:00[America/New_York]", "2026-03-15T02:30-04:00[America/New_York]",
                        "2026-03-22T02:30-04:00[America/New_York]"),
                nextN("30 2 * * 0", z("2026-03-01T00:00-05:00[America/New_York]"), 3));
    }
}
