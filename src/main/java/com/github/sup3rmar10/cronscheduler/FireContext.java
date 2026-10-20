package com.github.sup3rmar10.cronscheduler;

import java.time.Instant;

/**
 * Describes one run of a job.
 *
 * @param jobId        the job id
 * @param scheduledFor the nominal cron instant this run stands for (jitter is NOT included)
 * @param firedAt      the clock reading when the dispatcher handed the run to the worker pool
 * @param catchUp      true if this run is a misfire catch-up rather than an on-time run
 * @param coalesced    for FIRE_ONCE_NOW catch-ups: how many older missed occurrences were folded into this run
 */
public record FireContext(String jobId, Instant scheduledFor, Instant firedAt, boolean catchUp, int coalesced) {
}
