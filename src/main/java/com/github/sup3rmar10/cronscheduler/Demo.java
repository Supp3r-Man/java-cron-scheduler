package com.github.sup3rmar10.cronscheduler;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Scripted demo on a simulated clock: a week in America/New_York across the March 2026 spring-forward, then a
 * simulated stall that shows the three misfire policies. Nothing sleeps; the clock is moved from one due instant to
 * the next. Output is deterministic (one worker thread, lines printed after each step settles).
 */
public final class Demo {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss zzz", Locale.ENGLISH);

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        run(System.out);
    }

    public static void run(PrintStream out) throws Exception {
        weekAcrossDst(out);
        out.println();
        misfirePolicies(out);
    }

    private static void weekAcrossDst(PrintStream out) throws Exception {
        ZoneId ny = ZoneId.of("America/New_York");
        ZonedDateTime start = ZonedDateTime.of(2026, 3, 7, 0, 0, 0, 0, ny);
        ZonedDateTime end = start.plusDays(7);
        ManualClock clock = new ManualClock(start.toInstant(), ny);
        ZoneOffsetTransition tr = ny.getRules().nextTransition(start.toInstant());

        out.println("== 1. A simulated week in " + ny + ", " + start.toLocalDateTime() + " to " + end.toLocalDateTime());
        out.println("DST change: local " + tr.getDateTimeBefore() + " " + tr.getOffsetBefore() + " becomes "
                + tr.getDateTimeAfter() + " " + tr.getOffsetAfter() + " (the local hour in between does not exist)");

        List<String> fires = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try (CronScheduler scheduler = new CronScheduler(clock, 1)) {
            scheduler.addListener(new SchedulerListener() {
                @Override
                public void onFire(FireContext c) {
                    fires.add(String.format("%s  %-16s scheduled for %s", c.firedAt().atZone(ny).format(FMT), c.jobId(),
                            c.scheduledFor().atZone(ny).toLocalTime()));
                }

                @Override
                public void onComplete(FireContext c, Throwable failure) {
                    if (failure != null) {
                        failures.add("      ! " + c.jobId() + " failed: " + failure.getMessage() + " (other jobs unaffected)");
                    }
                }
            });
            scheduler.schedule(JobSpec.builder("nightly-report", CronExpression.parse("30 2 * * *"), c -> { }).zone(ny).build());
            scheduler.schedule(JobSpec.builder("six-hourly", CronExpression.parse("0 */6 * * *"), c -> { }).zone(ny).build());
            scheduler.schedule(JobSpec.builder("weekday-9am", CronExpression.parse("0 9 * * MON-FRI"), c -> { }).zone(ny).build());
            scheduler.schedule(JobSpec.builder("flaky-noon", CronExpression.parse("15 12 * * *"), c -> {
                if (c.scheduledFor().atZone(ny).getDayOfMonth() == 10) {
                    throw new IllegalStateException("simulated failure on the 10th");
                }
            }).zone(ny).build());
            scheduler.schedule(JobSpec.builder("jittered-warmup", CronExpression.parse("0 8 * * *"), c -> { })
                    .zone(ny).jitter(Duration.ofMinutes(5), 42L).build());
            scheduler.start();

            while (true) {
                Optional<Instant> next = scheduler.nextDueInstant();
                if (next.isEmpty() || !next.get().isBefore(end.toInstant())) {
                    break;
                }
                clock.set(next.get());
                if (!scheduler.awaitIdle(Duration.ofSeconds(10))) {
                    throw new IllegalStateException("scheduler did not settle");
                }
                synchronized (fires) {
                    fires.forEach(out::println);
                    fires.clear();
                }
                synchronized (failures) {
                    failures.forEach(out::println);
                    failures.clear();
                }
            }
            out.println();
            out.println("Summary (nightly-report is 02:30 local: it has no run on 2026-03-08 because 02:30 did not exist):");
            for (JobStatus s : scheduler.statuses()) {
                out.printf("  %-16s fired=%d succeeded=%d failed=%d%n", s.id(), s.fired(), s.succeeded(), s.failed());
            }
        }
    }

    private static void misfirePolicies(PrintStream out) throws Exception {
        ZoneId utc = ZoneId.of("UTC");
        ManualClock clock = new ManualClock(Instant.parse("2026-03-14T00:00:00Z"), utc);
        out.println("== 2. A 5.5 hour stall on an hourly job under each misfire policy (UTC)");
        out.println("Occurrences 01:00..05:00 all became due while 'stalled'; the clock then jumps to 05:30.");
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        try (CronScheduler scheduler = new CronScheduler(clock, 1)) {
            scheduler.addListener(new SchedulerListener() {
                @Override
                public void onFire(FireContext c) {
                    lines.add(String.format("  run   %-16s scheduled for %s catchUp=%s coalesced=%d", c.jobId(),
                            c.scheduledFor().atZone(utc).toLocalTime(), c.catchUp(), c.coalesced()));
                }

                @Override
                public void onSkipped(String jobId, SkipReason reason, int count, Instant last) {
                    lines.add(String.format("  skip  %-16s %d occurrence(s), reason %s", jobId, count, reason));
                }
            });
            CronExpression hourly = CronExpression.parse("0 * * * *");
            scheduler.schedule(JobSpec.builder("fire-once-now", hourly, c -> { }).misfirePolicy(MisfirePolicy.FIRE_ONCE_NOW).build());
            scheduler.schedule(JobSpec.builder("skip", hourly, c -> { }).misfirePolicy(MisfirePolicy.SKIP).build());
            scheduler.schedule(JobSpec.builder("fire-all-cap-3", hourly, c -> { })
                    .misfirePolicy(MisfirePolicy.FIRE_ALL).maxCatchUp(3).build());
            scheduler.start();
            clock.advance(Duration.ofHours(5).plusMinutes(30));
            if (!scheduler.awaitIdle(Duration.ofSeconds(10))) {
                throw new IllegalStateException("scheduler did not settle");
            }
            synchronized (lines) {
                lines.forEach(out::println);
            }
        }
    }
}
