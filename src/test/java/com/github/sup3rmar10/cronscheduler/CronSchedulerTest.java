package com.github.sup3rmar10.cronscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scheduler behaviour on a ManualClock. Nothing here sleeps; waits are condition waits with a hang-guard timeout. */
class CronSchedulerTest {
    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration GUARD = Duration.ofSeconds(10);
    private static final Duration MIN = Duration.ofMinutes(1);

    private ManualClock clock;
    private CronScheduler sched;
    private final List<CronScheduler> created = new ArrayList<>();

    /** Records every run. */
    static final class Recorder implements Job {
        final List<FireContext> runs = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run(FireContext c) {
            runs.add(c);
            threads.add(Thread.currentThread().getName());
        }

        List<Instant> scheduled() {
            synchronized (runs) {
                List<Instant> l = new ArrayList<>();
                runs.forEach(r -> l.add(r.scheduledFor()));
                return l;
            }
        }
    }

    /** A job that blocks until released; counts starts, completions and interruptions. */
    static final class Gate implements Job {
        final CountDownLatch release = new CountDownLatch(1);
        final Semaphore started = new Semaphore(0);
        final AtomicInteger completed = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void run(FireContext c) throws Exception {
            started.release();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate never released");
                }
                completed.incrementAndGet();
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            } finally {
                finished.countDown();
            }
        }

        void awaitStart() throws InterruptedException {
            assertTrue(started.tryAcquire(GUARD.toSeconds(), TimeUnit.SECONDS), "job never started");
        }
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sched = newScheduler(clock, 1); // one worker: runs execute in dispatch order, so order assertions are deterministic
    }

    @AfterEach
    void tearDown() {
        for (CronScheduler s : created) {
            s.shutdown(Duration.ofSeconds(2));
        }
    }

    private CronScheduler newScheduler(ManualClock c, int workers) {
        CronScheduler s = new CronScheduler(c, workers);
        created.add(s);
        s.start();
        return s;
    }

    private static JobSpec.Builder spec(String id, String expr, Job job) {
        return JobSpec.builder(id, CronExpression.parse(expr), job);
    }

    private void step(Duration d) {
        clock.advance(d);
        assertTrue(sched.awaitIdle(GUARD), "scheduler did not settle");
    }

    private void stepDispatched(Duration d) {
        clock.advance(d);
        assertTrue(sched.awaitDispatched(GUARD), "dispatcher did not settle");
    }

    private static void runUntil(CronScheduler s, ManualClock c, Instant end) {
        while (true) {
            Optional<Instant> n = s.nextDueInstant();
            if (n.isEmpty() || n.get().isAfter(end)) {
                return;
            }
            c.set(n.get());
            assertTrue(s.awaitIdle(GUARD));
        }
    }

    // ------------------------------------------------------------------ basic firing

    @Test
    void firesAtEachMatchingMinute() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN);
        step(MIN);
        step(MIN);
        assertEquals(List.of(T0.plus(MIN), T0.plus(MIN.multipliedBy(2)), T0.plus(MIN.multipliedBy(3))), r.scheduled());
    }

    @Test
    void doesNotFireBeforeTheDueInstant() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(Duration.ofSeconds(59));
        assertTrue(r.runs.isEmpty());
    }

    @Test
    void firesWhenTheClockLandsExactlyOnTheDueInstant() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "0 13 * * *", r).build());
        step(Duration.ofHours(1));
        assertEquals(List.of(T0.plus(Duration.ofHours(1))), r.scheduled());
    }

    @Test
    void aJobAddedAtAFireInstantWaitsForTheNextOne() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "0 12 * * *", r).build());
        step(Duration.ofSeconds(1));
        assertTrue(r.runs.isEmpty());
        assertEquals(Optional.of(Instant.parse("2026-06-02T12:00:00Z")), sched.nextDueInstant());
    }

    @Test
    void independentJobsKeepIndependentSchedules() {
        Recorder every = new Recorder();
        Recorder fiveMin = new Recorder();
        sched.schedule(spec("every", "* * * * *", every).build());
        sched.schedule(spec("five", "*/5 * * * *", fiveMin).build());
        for (int k = 0; k < 10; k++) {
            step(MIN);
        }
        assertEquals(10, every.runs.size());
        assertEquals(List.of(T0.plus(MIN.multipliedBy(5)), T0.plus(MIN.multipliedBy(10))), fiveMin.scheduled());
    }

    @Test
    void secondsFieldJobFiresEveryTenSeconds() {
        Recorder r = new Recorder();
        sched.schedule(spec("s", "*/10 * * * * *", r).build());
        for (int k = 0; k < 6; k++) {
            step(Duration.ofSeconds(10));
        }
        assertEquals(6, r.runs.size());
        assertEquals(T0.plusSeconds(60), r.scheduled().get(5));
    }

    @Test
    void fireContextCarriesTimesAndFlags() {
        Recorder r = new Recorder();
        sched.schedule(spec("ctx", "* * * * *", r).build());
        step(MIN);
        FireContext c = r.runs.get(0);
        assertEquals("ctx", c.jobId());
        assertEquals(T0.plus(MIN), c.scheduledFor());
        assertEquals(T0.plus(MIN), c.firedAt());
        assertFalse(c.catchUp());
        assertEquals(0, c.coalesced());
    }

    @Test
    void jobsRunOnWorkerThreadsAndListenersOnTheDispatcher() {
        Recorder r = new Recorder();
        List<String> fireThreads = Collections.synchronizedList(new ArrayList<>());
        sched.addListener(new SchedulerListener() {
            @Override
            public void onFire(FireContext c) {
                fireThreads.add(Thread.currentThread().getName());
            }
        });
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN);
        assertTrue(r.threads.get(0).startsWith("cron-worker-"), r.threads.toString());
        assertEquals(List.of("cron-dispatcher"), fireThreads);
    }

    @Test
    void exhaustedExpressionNeverFires() {
        Recorder r = new Recorder();
        sched.schedule(spec("never", "0 0 30 2 *", r).build());
        assertEquals(Optional.empty(), sched.nextDueInstant());
        step(Duration.ofDays(400));
        assertTrue(r.runs.isEmpty());
    }

    @Test
    void nextDueInstantIsTheEarliestAcrossJobsAndIgnoresPausedOnes() {
        sched.schedule(spec("hourly", "0 * * * *", new Recorder()).build());
        sched.schedule(spec("minutely", "* * * * *", new Recorder()).build());
        assertEquals(Optional.of(T0.plus(MIN)), sched.nextDueInstant());
        sched.pause("minutely");
        assertEquals(Optional.of(T0.plus(Duration.ofHours(1))), sched.nextDueInstant());
    }

    // ------------------------------------------------------------------ misfire policies

    @Test
    void anOccurrenceExactlyAtTheThresholdIsStillOnTime() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build()); // default threshold 1 s
        step(MIN.plusSeconds(1));
        assertEquals(1, r.runs.size());
        assertFalse(r.runs.get(0).catchUp());
        assertEquals(0, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void anOccurrenceJustBeyondTheThresholdIsAMisfire() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN.plusMillis(1001));
        assertEquals(1, r.runs.size());
        assertTrue(r.runs.get(0).catchUp());
    }

    @Test
    void skipDropsEveryLateOccurrence() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.SKIP).build());
        step(Duration.ofMinutes(10).plusSeconds(30));
        assertTrue(r.runs.isEmpty());
        JobStatus s = sched.status("a").orElseThrow();
        assertEquals(10, s.misfireSkipped());
        assertEquals(0, s.fired());
        // and it carries on normally afterwards
        step(Duration.ofSeconds(30));
        assertEquals(List.of(T0.plus(Duration.ofMinutes(11))), r.scheduled());
    }

    @Test
    void skipStillRunsTheOnTimeOccurrence() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.SKIP).build());
        step(Duration.ofMinutes(10));
        assertEquals(List.of(T0.plus(Duration.ofMinutes(10))), r.scheduled());
        assertFalse(r.runs.get(0).catchUp());
        assertEquals(9, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void fireOnceNowCoalescesEverythingIntoOneCatchUp() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.FIRE_ONCE_NOW).build());
        step(Duration.ofMinutes(10).plusSeconds(30));
        assertEquals(1, r.runs.size());
        FireContext c = r.runs.get(0);
        assertEquals(T0.plus(Duration.ofMinutes(10)), c.scheduledFor());
        assertTrue(c.catchUp());
        assertEquals(9, c.coalesced());
        assertEquals(9, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void fireOnceNowDoesNotRunTwiceWhenAnOnTimeOccurrenceExists() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(Duration.ofMinutes(10));
        assertEquals(1, r.runs.size());
        assertFalse(r.runs.get(0).catchUp());
        assertEquals(0, r.runs.get(0).coalesced());
        assertEquals(9, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void fireAllRunsTheMostRecentOnesUpToTheCapInOrder() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.FIRE_ALL).maxCatchUp(3).build());
        step(Duration.ofMinutes(10).plusSeconds(30));
        assertEquals(List.of(T0.plus(Duration.ofMinutes(8)), T0.plus(Duration.ofMinutes(9)), T0.plus(Duration.ofMinutes(10))),
                r.scheduled());
        assertTrue(r.runs.stream().allMatch(FireContext::catchUp));
        assertEquals(7, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void fireAllBelowTheCapRunsEveryMissedOccurrence() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.FIRE_ALL).maxCatchUp(20).build());
        step(Duration.ofMinutes(10).plusSeconds(30));
        assertEquals(10, r.runs.size());
        for (int k = 0; k < 10; k++) {
            assertEquals(T0.plus(MIN.multipliedBy(k + 1)), r.scheduled().get(k));
        }
        assertEquals(0, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void fireAllRunsLateOnesFirstAndTheOnTimeOneLast() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.FIRE_ALL).maxCatchUp(2).build());
        step(Duration.ofMinutes(10));
        assertEquals(List.of(T0.plus(Duration.ofMinutes(8)), T0.plus(Duration.ofMinutes(9)), T0.plus(Duration.ofMinutes(10))),
                r.scheduled());
        assertEquals(List.of(true, true, false), List.of(r.runs.get(0).catchUp(), r.runs.get(1).catchUp(), r.runs.get(2).catchUp()));
    }

    @Test
    void aWiderThresholdTurnsOlderOccurrencesIntoOnTimeOnes() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).misfirePolicy(MisfirePolicy.SKIP).misfireThreshold(MIN).build());
        step(Duration.ofMinutes(10).plusSeconds(30)); // the 10:00 occurrence is 30 s late (on time), 9:00 is 90 s late
        assertEquals(List.of(T0.plus(Duration.ofMinutes(10))), r.scheduled());
        assertEquals(9, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void aHugeJumpOnAPerSecondJobIsCoalescedQuickly() {
        Recorder r = new Recorder();
        // Threshold zero: even the newest occurrence (500 ms old) is late, so everything coalesces into one catch-up.
        sched.schedule(spec("a", "* * * * * *", r).misfireThreshold(Duration.ZERO).build());
        step(Duration.ofDays(1).plusMillis(500));
        assertEquals(1, r.runs.size());
        assertTrue(r.runs.get(0).catchUp());
        assertEquals(T0.plusSeconds(86400), r.runs.get(0).scheduledFor());
        assertEquals(86399, r.runs.get(0).coalesced());
        assertEquals(86399, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void listenerIsToldAboutMisfireAndOverlapSkips() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        sched.addListener(new SchedulerListener() {
            @Override
            public void onSkipped(String jobId, SkipReason reason, int count, Instant last) {
                events.add(jobId + ":" + reason + ":" + count + ":" + last);
            }
        });
        sched.schedule(spec("skipper", "* * * * *", new Recorder()).misfirePolicy(MisfirePolicy.SKIP).build());
        step(Duration.ofMinutes(3).plusSeconds(30));
        Gate gate = new Gate();
        sched.schedule(spec("gated", "*/10 * * * *", gate).noOverlap(true).build());
        stepDispatched(Duration.ofMinutes(10)); // T0+13m30s: the 12:10 occurrence is late, FIRE_ONCE_NOW dispatches a run
        gate.awaitStart();
        stepDispatched(Duration.ofMinutes(7)); // T0+20m30s: the 12:20 occurrence is due but the run is still in flight
        gate.release.countDown();
        assertTrue(sched.awaitIdle(GUARD));
        assertTrue(events.contains("skipper:MISFIRE:3:" + T0.plus(MIN.multipliedBy(3))), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("gated:OVERLAP:1:")), events.toString());
    }

    // ------------------------------------------------------------------ overlap

    @Test
    void noOverlapSkipsWhilePreviousRunIsInFlight() throws Exception {
        Gate gate = new Gate();
        sched.schedule(spec("g", "* * * * *", gate).noOverlap(true).build());
        stepDispatched(MIN);
        gate.awaitStart();
        stepDispatched(MIN);
        stepDispatched(MIN);
        JobStatus s = sched.status("g").orElseThrow();
        assertEquals(1, s.fired());
        assertEquals(2, s.overlapSkipped());
        assertEquals(1, s.inFlight());
        gate.release.countDown();
        assertTrue(sched.awaitIdle(GUARD));
        assertEquals(1, gate.completed.get());
    }

    @Test
    void noOverlapJobRunsAgainOnceThePreviousRunFinished() throws Exception {
        Gate gate = new Gate();
        gate.release.countDown(); // never blocks
        sched.schedule(spec("g", "* * * * *", gate).noOverlap(true).build());
        step(MIN);
        step(MIN);
        step(MIN);
        JobStatus s = sched.status("g").orElseThrow();
        assertEquals(3, s.fired());
        assertEquals(0, s.overlapSkipped());
        assertEquals(3, s.succeeded());
    }

    @Test
    void overlapIsAllowedByDefault() throws Exception {
        CronScheduler wide = newScheduler(clock, 4);
        Gate gate = new Gate();
        wide.schedule(spec("g", "* * * * *", gate).build());
        for (int k = 0; k < 3; k++) {
            clock.advance(MIN);
            assertTrue(wide.awaitDispatched(GUARD));
        }
        assertTrue(gate.started.tryAcquire(3, GUARD.toSeconds(), TimeUnit.SECONDS), "three runs should be concurrent");
        assertEquals(3, wide.status("g").orElseThrow().inFlight());
        gate.release.countDown();
        assertTrue(wide.awaitIdle(GUARD));
        assertEquals(3, gate.completed.get());
    }

    @Test
    void noOverlapWithFireAllOnlyDispatchesTheFirstCatchUpOfAPass() throws Exception {
        Gate gate = new Gate();
        sched.schedule(spec("g", "* * * * *", gate).noOverlap(true).misfirePolicy(MisfirePolicy.FIRE_ALL).maxCatchUp(5).build());
        stepDispatched(Duration.ofMinutes(10).plusSeconds(30));
        gate.awaitStart();
        JobStatus s = sched.status("g").orElseThrow();
        assertEquals(1, s.fired());
        assertEquals(4, s.overlapSkipped());
        assertEquals(5, s.misfireSkipped());
        gate.release.countDown();
        assertTrue(sched.awaitIdle(GUARD));
    }

    @Test
    void aSingleWorkerRunsJobsOneAtATime() throws Exception {
        CronScheduler one = newScheduler(clock, 1);
        Gate first = new Gate();
        Recorder second = new Recorder();
        one.schedule(spec("first", "* * * * *", first).build());
        one.schedule(spec("second", "* * * * *", second).build());
        clock.advance(MIN);
        assertTrue(one.awaitDispatched(GUARD));
        first.awaitStart();
        assertEquals(0, second.runs.size(), "the only worker is busy, so the second job must be queued");
        first.release.countDown();
        assertTrue(one.awaitIdle(GUARD));
        assertEquals(1, second.runs.size());
    }

    // ------------------------------------------------------------------ failures and listeners

    @Test
    void aFailingJobDoesNotAffectOthersAndKeepsItsSchedule() {
        Recorder ok = new Recorder();
        sched.schedule(spec("bad", "* * * * *", c -> {
            throw new IllegalStateException("boom");
        }).build());
        sched.schedule(spec("good", "* * * * *", ok).build());
        step(MIN);
        step(MIN);
        step(MIN);
        JobStatus bad = sched.status("bad").orElseThrow();
        assertEquals(3, bad.fired());
        assertEquals(3, bad.failed());
        assertEquals(0, bad.succeeded());
        assertTrue(bad.lastError().contains("boom"), bad.lastError());
        assertEquals(3, ok.runs.size());
        assertEquals(3, sched.status("good").orElseThrow().succeeded());
    }

    @Test
    void anErrorThrownByAJobIsIsolatedToo() {
        Recorder ok = new Recorder();
        sched.schedule(spec("err", "* * * * *", c -> {
            throw new AssertionError("an Error, not an Exception");
        }).build());
        sched.schedule(spec("good", "* * * * *", ok).build());
        step(MIN);
        step(MIN);
        assertEquals(2, sched.status("err").orElseThrow().failed());
        assertEquals(2, ok.runs.size());
    }

    @Test
    void aThrowingListenerDoesNotBreakDispatchOrWorkers() {
        sched.addListener(new SchedulerListener() {
            @Override
            public void onFire(FireContext c) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onComplete(FireContext c, Throwable f) {
                throw new RuntimeException("listener bug");
            }

            @Override
            public void onSkipped(String id, SkipReason r, int n, Instant l) {
                throw new RuntimeException("listener bug");
            }
        });
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN);
        step(MIN);
        assertEquals(2, r.runs.size());
        assertEquals(2, sched.status("a").orElseThrow().succeeded());
    }

    @Test
    void onCompleteReceivesTheFailure() {
        List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
        sched.addListener(new SchedulerListener() {
            @Override
            public void onComplete(FireContext c, Throwable failure) {
                seen.add(failure);
            }
        });
        RuntimeException boom = new RuntimeException("x");
        sched.schedule(spec("a", "* * * * *", c -> {
            throw boom;
        }).build());
        step(MIN);
        assertEquals(List.of(boom), seen);
    }

    // ------------------------------------------------------------------ cancel / pause / resume

    @Test
    void cancelStopsFurtherRuns() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN);
        assertTrue(sched.cancel("a"));
        step(MIN);
        step(MIN);
        assertEquals(1, r.runs.size());
        assertEquals(Optional.empty(), sched.status("a"));
        assertEquals(Optional.empty(), sched.nextDueInstant());
    }

    @Test
    void unknownIdsAreReportedAsFalse() {
        assertFalse(sched.cancel("nope"));
        assertFalse(sched.pause("nope"));
        assertFalse(sched.resume("nope"));
    }

    @Test
    void aCancelledIdCanBeReused() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        sched.schedule(spec("a", "* * * * *", first).build());
        sched.cancel("a");
        sched.schedule(spec("a", "* * * * *", second).build());
        step(MIN);
        assertTrue(first.runs.isEmpty());
        assertEquals(1, second.runs.size());
    }

    @Test
    void cancelDoesNotInterruptARunThatIsAlreadyInFlight() throws Exception {
        Gate gate = new Gate();
        sched.schedule(spec("g", "* * * * *", gate).build());
        stepDispatched(MIN);
        gate.awaitStart();
        assertTrue(sched.cancel("g"));
        gate.release.countDown();
        assertTrue(sched.awaitIdle(GUARD));
        assertEquals(1, gate.completed.get());
        assertFalse(gate.interrupted.get());
    }

    @Test
    void pauseStopsFiringAndResumeDoesNotCatchUp() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        step(MIN);
        assertTrue(sched.pause("a"));
        assertTrue(sched.status("a").orElseThrow().paused());
        step(Duration.ofMinutes(5));
        assertEquals(1, r.runs.size());
        assertTrue(sched.resume("a"));
        assertFalse(sched.status("a").orElseThrow().paused());
        step(MIN); // clock is at T0+6m, so the first fire after resuming is T0+7m
        assertEquals(List.of(T0.plus(MIN), T0.plus(MIN.multipliedBy(7))), r.scheduled());
        assertEquals(0, sched.status("a").orElseThrow().misfireSkipped());
    }

    @Test
    void resumingAJobThatIsNotPausedChangesNothing() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "*/5 * * * *", r).build());
        step(Duration.ofMinutes(2));
        assertTrue(sched.resume("a"));
        assertEquals(Optional.of(T0.plus(Duration.ofMinutes(5))), sched.nextDueInstant());
    }

    // ------------------------------------------------------------------ jitter

    private static long seedWithFirstJitterAtLeast(long minMillis, long bound) {
        long seed = 1;
        while (new Random(seed).nextLong(bound) < minMillis) {
            seed++;
        }
        return seed;
    }

    @Test
    void jitterDelaysTheDueTimeButNotTheNominalOrTheMisfireClock() {
        long bound = 30_000;
        long seed = seedWithFirstJitterAtLeast(1000, bound);
        Random expected = new Random(seed);
        long j0 = expected.nextLong(bound);
        long j1 = expected.nextLong(bound);
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).jitter(Duration.ofSeconds(30), seed).build());
        assertEquals(Optional.of(T0.plus(MIN).plusMillis(j0)), sched.nextDueInstant());
        step(MIN); // nominal time reached, jitter not yet elapsed
        assertTrue(r.runs.isEmpty());
        step(Duration.ofMillis(j0));
        assertEquals(List.of(T0.plus(MIN)), r.scheduled()); // scheduledFor is the nominal instant
        assertFalse(r.runs.get(0).catchUp(), "jitter must not count as lateness");
        assertEquals(T0.plus(MIN).plusMillis(j0), r.runs.get(0).firedAt());
        assertEquals(Optional.of(T0.plus(MIN.multipliedBy(2)).plusMillis(j1)), sched.nextDueInstant());
    }

    private List<Instant> dueSequence(long seed, int n) {
        ManualClock c = new ManualClock(T0);
        CronScheduler s = newScheduler(c, 1);
        s.schedule(spec("a", "* * * * *", new Recorder()).jitter(Duration.ofSeconds(30), seed).build());
        List<Instant> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Instant due = s.nextDueInstant().orElseThrow();
            Instant nominal = T0.plus(MIN.multipliedBy(k + 1));
            assertFalse(due.isBefore(nominal), "jitter must not fire early");
            assertTrue(due.isBefore(nominal.plusSeconds(30)), "jitter must stay below the bound");
            out.add(due);
            c.set(due);
            assertTrue(s.awaitIdle(GUARD));
        }
        return out;
    }

    @Test
    void jitterIsReproduciblePerSeedAndDiffersBetweenSeeds() {
        List<Instant> a1 = dueSequence(11, 8);
        List<Instant> a2 = dueSequence(11, 8);
        List<Instant> b = dueSequence(12, 8);
        assertEquals(a1, a2);
        assertNotEquals(a1, b);
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    void gracefulShutdownWaitsForRunningJobs() throws Exception {
        Gate gate = new Gate();
        sched.schedule(spec("g", "* * * * *", gate).build());
        stepDispatched(MIN);
        gate.awaitStart();
        CompletableFuture<Boolean> done = CompletableFuture.supplyAsync(() -> sched.shutdown(Duration.ofSeconds(20)));
        gate.release.countDown();
        assertTrue(done.get(GUARD.toSeconds(), TimeUnit.SECONDS), "shutdown should report a clean finish");
        assertEquals(1, gate.completed.get());
        assertFalse(gate.interrupted.get());
    }

    @Test
    void shutdownWithNoGraceInterruptsRunningJobs() throws Exception {
        Gate gate = new Gate();
        sched.schedule(spec("g", "* * * * *", gate).build());
        stepDispatched(MIN);
        gate.awaitStart();
        assertFalse(sched.shutdown(Duration.ZERO));
        assertTrue(gate.finished.await(GUARD.toSeconds(), TimeUnit.SECONDS));
        assertTrue(gate.interrupted.get());
        assertEquals(0, gate.completed.get());
    }

    @Test
    void shutdownStopsDispatchingAndRejectsNewWork() {
        Recorder r = new Recorder();
        sched.schedule(spec("a", "* * * * *", r).build());
        assertTrue(sched.shutdown(Duration.ofSeconds(5)));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(r.runs.isEmpty());
        assertThrows(IllegalStateException.class, () -> sched.schedule(spec("b", "* * * * *", new Recorder()).build()));
        assertThrows(IllegalStateException.class, () -> sched.start());
    }

    @Test
    void shutdownIsIdempotent() {
        assertTrue(sched.shutdown(Duration.ofSeconds(5)));
        assertTrue(sched.shutdown(Duration.ofSeconds(5)));
    }

    @Test
    void closeShutsDownLikeShutdown() {
        CronScheduler s = new CronScheduler(clock, 1);
        s.start();
        s.close();
        assertThrows(IllegalStateException.class, () -> s.schedule(spec("a", "* * * * *", new Recorder()).build()));
    }

    // ------------------------------------------------------------------ misc API

    @Test
    void duplicateIdsAreRejected() {
        sched.schedule(spec("a", "* * * * *", new Recorder()).build());
        assertThrows(IllegalArgumentException.class, () -> sched.schedule(spec("a", "0 * * * *", new Recorder()).build()));
    }

    @Test
    void statusesListJobsInInsertionOrderWithCounters() {
        sched.schedule(spec("first", "* * * * *", new Recorder()).build());
        sched.schedule(spec("second", "*/2 * * * *", new Recorder()).build());
        step(MIN);
        step(MIN);
        List<JobStatus> l = sched.statuses();
        assertEquals(List.of("first", "second"), List.of(l.get(0).id(), l.get(1).id()));
        assertEquals(2, l.get(0).succeeded());
        assertEquals(1, l.get(1).succeeded());
        assertEquals(T0.plus(MIN.multipliedBy(3)), l.get(0).nextScheduled());
    }

    @Test
    void awaitIdleReportsFalseWhenTheSchedulerWasNeverStarted() {
        CronScheduler idle = new CronScheduler(clock, 1);
        assertFalse(idle.awaitIdle(Duration.ZERO));
        idle.shutdown(Duration.ZERO);
    }

    @Test
    void constructorRejectsBadArguments() {
        assertThrows(NullPointerException.class, () -> new CronScheduler(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new CronScheduler(clock, 0));
    }

    @Test
    void jobOfAdaptsARunnable() {
        AtomicInteger n = new AtomicInteger();
        sched.schedule(JobSpec.builder("r", CronExpression.parse("* * * * *"), Job.of(n::incrementAndGet)).build());
        step(MIN);
        assertEquals(1, n.get());
    }

    // ------------------------------------------------------------------ DST through the scheduler

    @Test
    void springForwardGapDayHasNoRunForADailyJob() {
        ZoneId ny = ZoneId.of("America/New_York");
        ManualClock c = new ManualClock(ZonedDateTime.parse("2026-03-07T12:00-05:00[America/New_York]").toInstant(), ny);
        CronScheduler s = newScheduler(c, 1);
        Recorder r = new Recorder();
        s.schedule(spec("nightly", "30 2 * * *", r).zone(ny).build());
        runUntil(s, c, ZonedDateTime.parse("2026-03-10T12:00-04:00[America/New_York]").toInstant());
        assertEquals(List.of(Instant.parse("2026-03-09T06:30:00Z"), Instant.parse("2026-03-10T06:30:00Z")), r.scheduled());
    }

    @Test
    void fallBackOverlapRunsADailyJobTwiceThatDay() {
        ZoneId ny = ZoneId.of("America/New_York");
        ManualClock c = new ManualClock(ZonedDateTime.parse("2026-11-01T00:00-04:00[America/New_York]").toInstant(), ny);
        CronScheduler s = newScheduler(c, 1);
        Recorder r = new Recorder();
        s.schedule(spec("daily", "30 1 * * *", r).zone(ny).build());
        runUntil(s, c, ZonedDateTime.parse("2026-11-03T00:00-05:00[America/New_York]").toInstant());
        assertEquals(List.of(Instant.parse("2026-11-01T05:30:00Z"), Instant.parse("2026-11-01T06:30:00Z"),
                Instant.parse("2026-11-02T06:30:00Z")), r.scheduled());
    }
}
