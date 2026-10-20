package com.github.sup3rmar10.cronscheduler;

import java.time.Instant;

/**
 * A point-in-time copy of a job's counters.
 *
 * @param fired          runs handed to the worker pool (includes catch-ups)
 * @param misfireSkipped occurrences dropped, coalesced or capped by the misfire policy
 * @param overlapSkipped occurrences dropped because a no-overlap job was still running
 * @param inFlight       runs queued or running right now
 * @param nextScheduled  the next nominal fire instant, or null if the job is paused-with-no-future or exhausted
 */
public record JobStatus(String id, boolean paused, long fired, long succeeded, long failed, long misfireSkipped,
                        long overlapSkipped, int inFlight, String lastError, Instant nextScheduled) {
}
