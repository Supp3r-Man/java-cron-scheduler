package com.github.sup3rmar10.cronscheduler;

import static com.github.sup3rmar10.cronscheduler.CronField.DAY_OF_MONTH;
import static com.github.sup3rmar10.cronscheduler.CronField.DAY_OF_WEEK;
import static com.github.sup3rmar10.cronscheduler.CronField.HOUR;
import static com.github.sup3rmar10.cronscheduler.CronField.MINUTE;
import static com.github.sup3rmar10.cronscheduler.CronField.MONTH;
import static com.github.sup3rmar10.cronscheduler.CronField.SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CronParserTest {

    private static CronExpression p(String s) {
        return CronExpression.parse(s);
    }

    private static Set<Integer> set(int... v) {
        Set<Integer> s = new TreeSet<>();
        for (int x : v) {
            s.add(x);
        }
        return s;
    }

    private static Set<Integer> range(int a, int b) {
        Set<Integer> s = new TreeSet<>();
        IntStream.rangeClosed(a, b).forEach(s::add);
        return s;
    }

    @Test
    void fiveFieldExpressionFiresAtSecondZero() {
        CronExpression e = p("* * * * *");
        assertFalse(e.hasSeconds());
        assertEquals(set(0), e.values(SECOND));
    }

    @Test
    void sixFieldExpressionHasALeadingSecondsField() {
        CronExpression e = p("*/20 5 * * * *");
        assertTrue(e.hasSeconds());
        assertEquals(set(0, 20, 40), e.values(SECOND));
        assertEquals(set(5), e.values(MINUTE));
    }

    @Test
    void starExpandsToTheFullRangeOfEachField() {
        CronExpression e = p("* * * * *");
        assertEquals(range(0, 59), e.values(MINUTE));
        assertEquals(range(0, 23), e.values(HOUR));
        assertEquals(range(1, 31), e.values(DAY_OF_MONTH));
        assertEquals(range(1, 12), e.values(MONTH));
        assertEquals(range(0, 6), e.values(DAY_OF_WEEK));
    }

    @Test
    void listsOfSingleValues() {
        CronExpression e = p("5,10,15 1,2 * * *");
        assertEquals(set(5, 10, 15), e.values(MINUTE));
        assertEquals(set(1, 2), e.values(HOUR));
    }

    @Test
    void rangesAreInclusive() {
        assertEquals(set(10, 11, 12), p("10-12 * * * *").values(MINUTE));
    }

    @Test
    void starStepStartsAtTheFieldMinimum() {
        assertEquals(set(0, 15, 30, 45), p("*/15 * * * *").values(MINUTE));
        assertEquals(set(1, 4, 7, 10), p("* * * */3 *").values(MONTH));
        assertEquals(set(1, 11, 21, 31), p("* * */10 * *").values(DAY_OF_MONTH));
    }

    @Test
    void rangeWithStep() {
        assertEquals(set(10, 20, 30), p("10-30/10 * * * *").values(MINUTE));
    }

    @Test
    void startWithStepRunsToTheFieldMaximum() {
        assertEquals(set(5, 25, 45), p("5/20 * * * *").values(MINUTE));
    }

    @Test
    void listsMayMixSinglesRangesAndSteps() {
        assertEquals(set(1, 5, 6, 7, 20, 30, 40), p("1,5-7,20-40/10 * * * *").values(MINUTE));
    }

    @Test
    void stepEqualToFieldSizeIsAccepted() {
        assertEquals(set(0), p("*/60 * * * *").values(MINUTE));
    }

    @Test
    void monthNamesAreCaseInsensitiveAndCanFormRanges() {
        assertEquals(set(1, 3, 12), p("0 0 1 JAN,mar,Dec *").values(MONTH));
        assertEquals(set(1, 2, 3), p("0 0 1 jan-MAR *").values(MONTH));
    }

    @Test
    void dayOfWeekNames() {
        assertEquals(range(1, 5), p("0 9 * * MON-FRI").values(DAY_OF_WEEK));
        assertEquals(set(0, 6), p("0 9 * * sat,sun").values(DAY_OF_WEEK));
    }

    @Test
    void zeroAndSevenAreBothSunday() {
        assertEquals(set(0), p("* * * * 7").values(DAY_OF_WEEK));
        assertEquals(set(0), p("* * * * 0").values(DAY_OF_WEEK));
        assertEquals(range(0, 6), p("* * * * 0-7").values(DAY_OF_WEEK));
        assertEquals(set(5, 6, 0), p("* * * * 5-7").values(DAY_OF_WEEK));
    }

    @Test
    void aDayOfWeekRangeMayEndInSunday() {
        assertEquals(set(5, 6, 0), p("* * * * FRI-SUN").values(DAY_OF_WEEK));
        assertEquals(set(6, 0), p("* * * * 6-0").values(DAY_OF_WEEK));
    }

    @Test
    void dayOfWeekStarStepStopsAtSaturday() {
        assertEquals(set(0, 2, 4, 6), p("* * * * */2").values(DAY_OF_WEEK));
    }

    @Test
    void whitespaceIsNormalised() {
        CronExpression e = p("  0   12 \t * * 1 ");
        assertEquals("0 12 * * 1", e.expression());
        assertEquals("0 12 * * 1", e.toString());
    }

    @Test
    void questionMarkMeansNoSpecificValueInDayFields() {
        assertEquals(range(1, 31), p("0 0 ? * MON").values(DAY_OF_MONTH));
        assertEquals(range(0, 6), p("0 0 15 * ?").values(DAY_OF_WEEK));
    }

    @Test
    void lastDayOfMonth() {
        CronExpression e = p("0 0 L * *");
        assertTrue(e.lastDayOfMonth());
        assertTrue(e.values(DAY_OF_MONTH).isEmpty());
    }

    @Test
    void lastDayOfMonthCanBeListedWithOtherDays() {
        CronExpression e = p("0 0 L,15 * *");
        assertTrue(e.lastDayOfMonth());
        assertEquals(set(15), e.values(DAY_OF_MONTH));
    }

    @Test
    void lastWeekdayOfMonth() {
        assertEquals(set(5), p("0 0 * * 5L").lastDaysOfWeek());
        assertEquals(set(0, 6), p("0 0 * * 7L,6L").lastDaysOfWeek());
        assertFalse(p("0 0 * * 5L").lastDayOfMonth());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "* * * *", "* * * * * * *", "@daily",
            "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *", "* * * 0 *", "* * * 13 *", "* * * * 8",
            "60 * * * * *",
            "*/0 * * * *", "*/61 * * * *", "*/x * * * *", "1/ * * * *",
            "5-1 * * * *", "22-2 * * * *", "1-2-3 * * * *", "- * * * *", "1- * * * *", "-5 * * * *",
            "a * * * *", "1.5 * * * *", "99999 * * * *", "* * * FOO *", "* * * * FUNDAY",
            "1,,2 * * * *", "1, * * * *", ", * * * *",
            "? * * * *", "* ? * * *", "* * * ? *",
            "L * * * *", "* * L-3 * *", "* * L/2 * *", "* * * * 9L", "* * * * 5L/2", "* * * * 7/2"})
    void malformedExpressionsAreRejected(String expr) {
        assertThrows(CronParseException.class, () -> p(expr));
    }

    @Test
    void parseErrorsNameTheFieldAndText() {
        CronParseException e = assertThrows(CronParseException.class, () -> p("61 * * * *"));
        assertTrue(e.getMessage().contains("minute"), e.getMessage());
        assertTrue(e.getMessage().contains("61"), e.getMessage());
        e = assertThrows(CronParseException.class, () -> p("* * * * * * *"));
        assertTrue(e.getMessage().contains("7"), e.getMessage());
    }

    @Test
    void nullExpressionIsANullPointerException() {
        assertThrows(NullPointerException.class, () -> CronExpression.parse(null));
    }

    @Test
    void defaultDayRuleIsOr() {
        assertEquals(DayRule.OR, p("0 0 13 * FRI").dayRule());
        assertEquals(DayRule.AND, CronExpression.parse("0 0 13 * FRI", DayRule.AND).dayRule());
    }

    @Test
    void equalityIgnoresWhitespaceButNotTheDayRule() {
        assertEquals(p("0  0 * * *"), p("0 0 * * *"));
        assertEquals(p("0  0 * * *").hashCode(), p("0 0 * * *").hashCode());
        assertNotEquals(p("0 0 13 * FRI"), CronExpression.parse("0 0 13 * FRI", DayRule.AND));
        assertNotEquals(p("0 0 * * *"), p("0 1 * * *"));
    }
}
