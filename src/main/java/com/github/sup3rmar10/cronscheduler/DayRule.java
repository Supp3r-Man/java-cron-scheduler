package com.github.sup3rmar10.cronscheduler;

/**
 * How day-of-month and day-of-week combine when BOTH are restricted (neither is the literal {@code *} or
 * {@code ?}). When at least one of them is a wildcard the two rules give the same answer.
 */
public enum DayRule {
    /**
     * Classic Vixie-cron behaviour and the default: a day matches if EITHER the day-of-month OR the day-of-week
     * matches. {@code 0 0 13 * FRI} means "the 13th, and every Friday".
     */
    OR,
    /** A day matches only if BOTH match. {@code 0 0 13 * FRI} then means "Friday the 13th". */
    AND
}
