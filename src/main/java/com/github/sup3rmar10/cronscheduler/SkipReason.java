package com.github.sup3rmar10.cronscheduler;

/** Why an occurrence did not run. */
public enum SkipReason {
    /** Late beyond the threshold and dropped by the misfire policy (or beyond the FIRE_ALL cap, or coalesced). */
    MISFIRE,
    /** The job is configured no-overlap and its previous run was still queued or running. */
    OVERLAP
}
