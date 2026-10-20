package com.github.sup3rmar10.cronscheduler;

/**
 * What to do with occurrences that were due but are more than the job's misfire threshold late when the
 * dispatcher gets to them (the process was stalled, the clock jumped, the pool was busy, ...).
 * Occurrences within the threshold are "on time" and always fire normally.
 */
public enum MisfirePolicy {
    /**
     * Default. If any occurrence is late and none is on time, run the job once now (scheduledFor = the latest missed
     * occurrence, {@code coalesced} = how many older ones were folded into it). If an on-time occurrence exists in
     * the same pass, that run already covers "now" and the late ones are just counted as skipped.
     */
    FIRE_ONCE_NOW,
    /** Drop every late occurrence (counted as skipped); only on-time occurrences run. */
    SKIP,
    /**
     * Run late occurrences one by one in chronological order, but at most {@code maxCatchUp} of them: the most recent
     * ones are kept and older ones are counted as skipped.
     */
    FIRE_ALL
}
