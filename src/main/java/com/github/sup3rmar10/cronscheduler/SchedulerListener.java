package com.github.sup3rmar10.cronscheduler;

import java.time.Instant;

/**
 * Observer hooks. {@code onFire} and {@code onSkipped} run on the dispatcher thread while the scheduler lock is
 * held, so they must be quick and must not block or call back into blocking scheduler methods.
 * {@code onComplete} runs on the worker thread right after the job. Exceptions thrown by a listener are swallowed.
 */
public interface SchedulerListener {
    default void onFire(FireContext context) {
    }

    /** {@code count} occurrences were skipped; {@code lastScheduledFor} is the latest of them. */
    default void onSkipped(String jobId, SkipReason reason, int count, Instant lastScheduledFor) {
    }

    /** {@code failure} is null when the job returned normally. */
    default void onComplete(FireContext context, Throwable failure) {
    }
}
