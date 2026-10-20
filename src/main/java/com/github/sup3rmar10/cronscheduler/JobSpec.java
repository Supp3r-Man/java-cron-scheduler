package com.github.sup3rmar10.cronscheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** Immutable job definition. Build with {@link #builder}. */
public final class JobSpec {
    private final String id;
    private final CronExpression cron;
    private final Job job;
    private final ZoneId zone;
    private final MisfirePolicy misfirePolicy;
    private final int maxCatchUp;
    private final boolean noOverlap;
    private final Duration misfireThreshold;
    private final Duration maxJitter;
    private final long jitterSeed;

    private JobSpec(Builder b) {
        this.id = b.id;
        this.cron = b.cron;
        this.job = b.job;
        this.zone = b.zone;
        this.misfirePolicy = b.misfirePolicy;
        this.maxCatchUp = b.maxCatchUp;
        this.noOverlap = b.noOverlap;
        this.misfireThreshold = b.misfireThreshold;
        this.maxJitter = b.maxJitter;
        this.jitterSeed = b.jitterSeed;
    }

    public static Builder builder(String id, CronExpression cron, Job job) {
        return new Builder(id, cron, job);
    }

    public String id() {
        return id;
    }

    public CronExpression cron() {
        return cron;
    }

    public Job job() {
        return job;
    }

    public ZoneId zone() {
        return zone;
    }

    public MisfirePolicy misfirePolicy() {
        return misfirePolicy;
    }

    public int maxCatchUp() {
        return maxCatchUp;
    }

    public boolean noOverlap() {
        return noOverlap;
    }

    public Duration misfireThreshold() {
        return misfireThreshold;
    }

    public Duration maxJitter() {
        return maxJitter;
    }

    public long jitterSeed() {
        return jitterSeed;
    }

    public static final class Builder {
        private final String id;
        private final CronExpression cron;
        private final Job job;
        private ZoneId zone = ZoneOffset.UTC;
        private MisfirePolicy misfirePolicy = MisfirePolicy.FIRE_ONCE_NOW;
        private int maxCatchUp = 10;
        private boolean noOverlap;
        private Duration misfireThreshold = Duration.ofSeconds(1);
        private Duration maxJitter = Duration.ZERO;
        private long jitterSeed;

        private Builder(String id, CronExpression cron, Job job) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("job id must not be blank");
            }
            this.id = id;
            this.cron = Objects.requireNonNull(cron, "cron");
            this.job = Objects.requireNonNull(job, "job");
        }

        /** Zone in which the cron expression is evaluated. Default UTC. */
        public Builder zone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone, "zone");
            return this;
        }

        /** Default FIRE_ONCE_NOW. */
        public Builder misfirePolicy(MisfirePolicy p) {
            this.misfirePolicy = Objects.requireNonNull(p, "policy");
            return this;
        }

        /** Cap for FIRE_ALL catch-up runs per dispatch pass. Default 10, must be at least 1. */
        public Builder maxCatchUp(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("maxCatchUp must be >= 1, was " + n);
            }
            this.maxCatchUp = n;
            return this;
        }

        /** If true, an occurrence is skipped (counted as OVERLAP) while the previous run is queued or running. */
        public Builder noOverlap(boolean b) {
            this.noOverlap = b;
            return this;
        }

        /** An occurrence more than this late (strictly) is a misfire. Default 1 second. */
        public Builder misfireThreshold(Duration d) {
            Objects.requireNonNull(d, "threshold");
            if (d.isNegative()) {
                throw new IllegalArgumentException("misfireThreshold must not be negative");
            }
            this.misfireThreshold = d;
            return this;
        }

        /**
         * Delays each run by a pseudo-random amount in [0, max). The k-th delay is the k-th value of
         * {@code new Random(seed).nextLong(max.toMillis())}, so it is reproducible. Zero (the default) disables it.
         */
        public Builder jitter(Duration max, long seed) {
            Objects.requireNonNull(max, "max");
            if (max.isNegative() || (!max.isZero() && max.toMillis() < 1)) {
                throw new IllegalArgumentException("jitter must be zero or at least 1 ms, was " + max);
            }
            this.maxJitter = max;
            this.jitterSeed = seed;
            return this;
        }

        public JobSpec build() {
            return new JobSpec(this);
        }
    }
}
