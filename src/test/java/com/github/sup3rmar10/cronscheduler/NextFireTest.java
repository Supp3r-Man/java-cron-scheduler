package com.github.sup3rmar10.cronscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Next-fire computation away from DST changes (all UTC unless stated). */
class NextFireTest {
    private static final ZoneId UTC = ZoneOffset.UTC;

    private static Instant i(String iso) {
        return Instant.parse(iso);
    }

    private static String next(String expr, String afterIso) {
        return CronExpression.parse(expr).nextFire(i(afterIso), UTC).map(z -> z.toInstant().toString()).orElse("none");
    }

    private static List<String> nextN(CronExpression e, String afterIso, int n) {
        return e.nextFires(i(afterIso), UTC, n).stream().map(z -> z.toInstant().toString()).collect(Collectors.toList());
    }

    @Test
    void everyMinute() {
        assertEquals("2026-06-01T12:01:00Z", next("* * * * *", "2026-06-01T12:00:30Z"));
    }

    @Test
    void resultIsStrictlyAfterAnExactMatch() {
        assertEquals("2026-06-01T12:01:00Z", next("* * * * *", "2026-06-01T12:00:00Z"));
    }

    @Test
    void rollsOverHourDayMonthAndYear() {
        assertEquals("2026-06-01T13:00:00Z", next("* * * * *", "2026-06-01T12:59:30Z"));
        assertEquals("2026-06-02T00:00:00Z", next("* * * * *", "2026-06-01T23:59:00Z"));
        assertEquals("2026-07-01T00:00:00Z", next("* * * * *", "2026-06-30T23:59:59Z"));
        assertEquals("2027-01-01T00:00:00Z", next("* * * * *", "2026-12-31T23:59:30Z"));
    }

    @Test
    void fixedTimeOfDay() {
        assertEquals("2026-06-01T14:30:00Z", next("30 14 * * *", "2026-06-01T14:29:59Z"));
        assertEquals("2026-06-02T14:30:00Z", next("30 14 * * *", "2026-06-01T14:30:00Z"));
    }

    @Test
    void secondsField() {
        assertEquals("2026-06-01T12:00:15Z", next("*/15 * * * * *", "2026-06-01T12:00:00Z"));
        assertEquals("2026-06-01T12:01:00Z", next("*/15 * * * * *", "2026-06-01T12:00:45Z"));
        assertEquals("2026-06-02T10:15:30Z", next("30 15 10 * * *", "2026-06-01T10:15:30Z"));
    }

    @Test
    void subSecondPartsOfTheStartAreHandled() {
        assertEquals("2026-06-01T12:00:01Z", next("* * * * * *", "2026-06-01T12:00:00.500Z"));
        assertEquals("2026-06-01T12:00:02Z", next("* * * * * *", "2026-06-01T12:00:01.000Z"));
        assertEquals("2026-06-01T12:01:00Z", next("* * * * *", "2026-06-01T12:00:00.000000001Z"));
    }

    @Test
    void stepsAcrossAnHourRange() {
        CronExpression e = CronExpression.parse("*/20 8-9 * * *");
        assertEquals(List.of("2026-06-01T08:00:00Z", "2026-06-01T08:20:00Z", "2026-06-01T08:40:00Z",
                        "2026-06-01T09:00:00Z", "2026-06-01T09:20:00Z", "2026-06-01T09:40:00Z", "2026-06-02T08:00:00Z"),
                nextN(e, "2026-06-01T00:00:00Z", 7));
    }

    @Test
    void thirtyFirstSkipsShortMonths() {
        CronExpression e = CronExpression.parse("0 0 31 * *");
        assertEquals(List.of("2026-01-31T00:00:00Z", "2026-03-31T00:00:00Z", "2026-05-31T00:00:00Z",
                        "2026-07-31T00:00:00Z", "2026-08-31T00:00:00Z", "2026-10-31T00:00:00Z", "2026-12-31T00:00:00Z"),
                nextN(e, "2026-01-01T00:00:00Z", 7));
    }

    @Test
    void lastDayOfMonthTracksMonthLengthAndLeapYears() {
        CronExpression e = CronExpression.parse("0 0 L * *");
        assertEquals(List.of("2026-01-31T00:00:00Z", "2026-02-28T00:00:00Z", "2026-03-31T00:00:00Z", "2026-04-30T00:00:00Z"),
                nextN(e, "2026-01-01T00:00:00Z", 4));
        assertEquals("2028-02-29T00:00:00Z", next("0 0 L * *", "2028-02-01T00:00:00Z"));
        assertEquals("2100-02-28T00:00:00Z", next("0 0 L * *", "2100-02-01T00:00:00Z"));
    }

    @Test
    void leapDayFiresOnlyInLeapYears() {
        assertEquals("2028-02-29T00:00:00Z", next("0 0 29 2 *", "2026-01-01T00:00:00Z"));
        assertEquals("2032-02-29T00:00:00Z", next("0 0 29 2 *", "2028-02-29T00:00:00Z"));
    }

    @Test
    void leapDaySkipsTheNonLeapCentury() {
        // 2096 is a leap year, 2100 is not (divisible by 100 but not 400), so the next one is 2104.
        assertEquals("2104-02-29T00:00:00Z", next("0 0 29 2 *", "2096-03-01T00:00:00Z"));
    }

    @Test
    void impossibleDatesHaveNoNextFire() {
        assertEquals("none", next("0 0 30 2 *", "2026-01-01T00:00:00Z"));
        assertEquals("none", next("0 0 31 4,6,9,11 *", "2026-01-01T00:00:00Z"));
        assertTrue(CronExpression.parse("0 0 30 2 *").nextFires(i("2026-01-01T00:00:00Z"), UTC, 3).isEmpty());
    }

    @Test
    void rareButPossibleDateIsFoundWithinTheHorizon() {
        // Leap day that is also a Monday, AND semantics: compute the expected year with plain java.time.
        int year = 2026;
        LocalDate expected = null;
        for (int y = year; y < year + 49 && expected == null; y++) {
            if (java.time.Year.isLeap(y) && LocalDate.of(y, 2, 29).getDayOfWeek() == DayOfWeek.MONDAY) {
                expected = LocalDate.of(y, 2, 29);
            }
        }
        CronExpression e = CronExpression.parse("0 0 29 2 MON", DayRule.AND);
        Optional<ZonedDateTime> got = e.nextFire(i("2026-01-01T00:00:00Z"), UTC);
        assertTrue(got.isPresent());
        assertEquals(expected, got.get().toLocalDate());
    }

    @Test
    void singleDayOfWeek() {
        assertEquals("2026-06-08T09:00:00Z", next("0 9 * * MON", "2026-06-01T09:00:00Z")); // 2026-06-01 is a Monday
        assertEquals("2026-06-01T09:00:00Z", next("0 9 * * MON", "2026-05-31T09:00:00Z"));
    }

    @Test
    void weekdayRange() {
        // Friday 2026-06-05 09:00 -> next is Monday 2026-06-08.
        assertEquals("2026-06-08T09:00:00Z", next("0 9 * * MON-FRI", "2026-06-05T09:00:00Z"));
    }

    @Test
    void dayOfMonthAndDayOfWeekAreOredWhenBothAreRestricted() {
        // 2026-04-13 is a Monday, so it matches only by day-of-month; Fridays match only by day-of-week.
        CronExpression e = CronExpression.parse("0 0 13 * FRI");
        assertEquals(List.of("2026-04-03T00:00:00Z", "2026-04-10T00:00:00Z", "2026-04-13T00:00:00Z",
                        "2026-04-17T00:00:00Z", "2026-04-24T00:00:00Z", "2026-05-01T00:00:00Z"),
                nextN(e, "2026-04-01T00:00:00Z", 6));
    }

    @Test
    void dayOfMonthAndDayOfWeekAreAndedWhenAskedTo() {
        CronExpression e = CronExpression.parse("0 0 13 * FRI", DayRule.AND);
        assertEquals(List.of("2026-02-13T00:00:00Z", "2026-03-13T00:00:00Z", "2026-11-13T00:00:00Z"),
                nextN(e, "2026-01-01T00:00:00Z", 3));
        ZonedDateTime fourth = e.nextFires(i("2026-01-01T00:00:00Z"), UTC, 4).get(3);
        assertEquals(13, fourth.getDayOfMonth());
        assertEquals(DayOfWeek.FRIDAY, fourth.getDayOfWeek());
    }

    @Test
    void aWildcardFieldMakesBothRulesAgree() {
        for (String expr : List.of("0 0 * * MON", "0 0 1 * *", "0 0 ? * MON", "0 0 15 * ?")) {
            List<ZonedDateTime> or = CronExpression.parse(expr, DayRule.OR).nextFires(i("2026-01-01T00:00:00Z"), UTC, 20);
            List<ZonedDateTime> and = CronExpression.parse(expr, DayRule.AND).nextFires(i("2026-01-01T00:00:00Z"), UTC, 20);
            assertEquals(or, and, expr);
            assertEquals(20, or.size());
        }
    }

    @Test
    void starWithAStepCountsAsRestrictedNotAsWildcard() {
        // dom */2 = odd days 1,3,...,31. Restricted + Monday under OR: odd days OR Mondays.
        // 2026-06-01 is a Monday; after it: Jun 3 (odd), Jun 5, Jun 7, Jun 8 (Monday, even day), Jun 9.
        CronExpression e = CronExpression.parse("0 0 */2 * MON");
        assertEquals(List.of("2026-06-03T00:00:00Z", "2026-06-05T00:00:00Z", "2026-06-07T00:00:00Z",
                        "2026-06-08T00:00:00Z", "2026-06-09T00:00:00Z"),
                nextN(e, "2026-06-01T00:00:00Z", 5));
    }

    @Test
    void questionMarkBehavesLikeStar() {
        assertEquals(nextN(CronExpression.parse("0 0 * * MON"), "2026-01-01T00:00:00Z", 10),
                nextN(CronExpression.parse("0 0 ? * MON"), "2026-01-01T00:00:00Z", 10));
    }

    @Test
    void lastDayOfMonthCombinesWithOtherDaysInAList() {
        CronExpression e = CronExpression.parse("0 0 L,15 * *");
        assertEquals(List.of("2026-02-15T00:00:00Z", "2026-02-28T00:00:00Z", "2026-03-15T00:00:00Z", "2026-03-31T00:00:00Z"),
                nextN(e, "2026-02-01T00:00:00Z", 4));
    }

    @Test
    void lastFridayOfTheMonth() {
        CronExpression e = CronExpression.parse("0 0 * * 5L");
        assertEquals(List.of("2026-01-30T00:00:00Z", "2026-02-27T00:00:00Z", "2026-03-27T00:00:00Z"),
                nextN(e, "2026-01-01T00:00:00Z", 3));
    }

    @Test
    void lastSaturdayIsNotTheSaturdayAWeekBeforeTheLast() {
        // Saturdays in January 2026: 3, 10, 17, 24, 31. The 24th is exactly 7 days before month end and must NOT match.
        assertEquals(List.of("2026-01-31T00:00:00Z", "2026-02-28T00:00:00Z"),
                nextN(CronExpression.parse("0 0 * * 6L"), "2026-01-01T00:00:00Z", 2));
    }

    @Test
    void lastSundayCanBeSixDaysBeforeMonthEnd() {
        // Sundays in January 2026: 4, 11, 18, 25. The 25th is the last one although 6 days remain in the month.
        assertEquals(List.of("2026-01-25T00:00:00Z", "2026-02-22T00:00:00Z"),
                nextN(CronExpression.parse("0 0 * * 7L"), "2026-01-01T00:00:00Z", 2));
    }

    @Test
    void monthNameRange() {
        CronExpression e = CronExpression.parse("0 0 1 JUN-AUG *");
        assertEquals(List.of("2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z", "2027-06-01T00:00:00Z"),
                nextN(e, "2026-01-01T00:00:00Z", 4));
    }

    @Test
    void yearlyExpressionAtTheBoundaryMovesToNextYear() {
        assertEquals("2027-01-01T00:00:00Z", next("0 0 1 JAN *", "2026-01-01T00:00:00Z"));
    }

    @Test
    void fixedOffsetZonesApplyTheirOffset() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata"); // +05:30, no DST
        ZonedDateTime n = CronExpression.parse("0 9 * * *").nextFire(i("2026-06-01T00:00:00Z"), kolkata).orElseThrow();
        assertEquals(Instant.parse("2026-06-01T03:30:00Z"), n.toInstant());
        assertEquals(kolkata, n.getZone());
    }

    @Test
    void zonedOverloadUsesTheZoneOfItsArgument() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZonedDateTime after = ZonedDateTime.of(2026, 6, 1, 8, 59, 0, 0, tokyo);
        ZonedDateTime n = CronExpression.parse("0 9 * * *").nextFire(after).orElseThrow();
        assertEquals(ZonedDateTime.of(2026, 6, 1, 9, 0, 0, 0, tokyo), n);
    }

    @Test
    void nextFiresReturnsConsecutiveStrictlyIncreasingTimes() {
        List<ZonedDateTime> l = CronExpression.parse("*/7 * * * *").nextFires(i("2026-06-01T00:00:00Z"), UTC, 30);
        assertEquals(30, l.size());
        for (int k = 1; k < l.size(); k++) {
            assertTrue(l.get(k).isAfter(l.get(k - 1)));
        }
    }

    @Test
    void matchesChecksTheWallClockFields() {
        CronExpression e = CronExpression.parse("30 14 * * *");
        assertTrue(e.matches(LocalDateTime.of(2026, 6, 1, 14, 30, 0)));
        assertFalse(e.matches(LocalDateTime.of(2026, 6, 1, 14, 30, 1)));
        assertFalse(e.matches(LocalDateTime.of(2026, 6, 1, 14, 30, 0, 5)));
        assertFalse(e.matches(LocalDateTime.of(2026, 6, 1, 14, 31, 0)));
        assertTrue(CronExpression.parse("5 30 14 * * *").matches(LocalDateTime.of(2026, 6, 1, 14, 30, 5)));
    }

    @Test
    void everyResultOfNextFireSatisfiesMatches() {
        CronExpression e = CronExpression.parse("*/13 2-20/3 1-28 */2 MON-THU");
        for (ZonedDateTime z : e.nextFires(i("2026-01-01T00:00:00Z"), UTC, 200)) {
            assertTrue(e.matches(z.toLocalDateTime()), z.toString());
        }
    }
}
