package com.github.sup3rmar10.cronscheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An in-process cron scheduler: ONE dispatcher thread decides what is due, a fixed worker pool runs the jobs.
 *
 * <h2>Time</h2>
 * All time comes from the injected {@link Clock}. With a {@link ManualClock} the dispatcher does not wait on
 * wall-clock time at all: it sleeps on a condition and is woken by the clock's change notification, so tests and the
 * demo can simulate days in milliseconds without a single sleep. With any other clock it waits (bounded to 30 s per
 * wait) until the next due instant.
 *
 * <h2>Semantics worth knowing</h2>
 * <ul>
 *   <li>A job added at time T first fires at the first cron instant strictly after T.</li>
 *   <li>Misfires: see {@link MisfirePolicy}. Lateness is measured against nominal time plus jitter.</li>
 *   <li>No-overlap jobs skip (do not queue) an occurrence while the previous run is queued or running.
 *       With FIRE_ALL this means only the first catch-up run of a pass is dispatched.</li>
 *   <li>Failure isolation: a job that throws (even an Error) is recorded, its schedule continues, and no other
 *       job or the dispatcher is affected.</li>
 *   <li>Pause stops firing; resume recomputes the next fire from "now", so time spent paused is never caught up.
 *       Cancel removes the job; a run already dispatched is allowed to finish.</li>
 *   <li>Shutdown stops the dispatcher (due-but-undispatched occurrences are dropped), lets already-dispatched runs
 *       finish for up to the grace period, then interrupts the rest.</li>
 * </ul>
 */
public final class CronScheduler implements AutoCloseable {
    private static final long MAX_REAL_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Clock clock;
    private final boolean simulated;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup = lock.newCondition();
    private final Condition idle = lock.newCondition();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<SchedulerListener> listeners = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor workers;
    private Thread dispatcher;
    private boolean started;
    private boolean stopping;
    private boolean settled;
    private int totalInFlight;

    private static final class Entry {
        final JobSpec spec;
        final Random jitter;
        Instant nextNominal;
        Instant nextDue;
        boolean paused;
        boolean cancelled;
        int inFlight;
        long fired;
        long succeeded;
        long failed;
        long misfireSkipped;
        long overlapSkipped;
        String lastError;

        Entry(JobSpec spec) {
            this.spec = spec;
            this.jitter = new Random(spec.jitterSeed());
        }
    }

    private final class RunTask implements Runnable {
        final Entry entry;
        final FireContext ctx;

        RunTask(Entry entry, FireContext ctx) {
            this.entry = entry;
            this.ctx = ctx;
        }

        @Override
        public void run() {
            runJob(entry, ctx);
        }
    }

    public CronScheduler(Clock clock, int workerThreads) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1, was " + workerThreads);
        }
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "cron-worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.simulated = clock instanceof ManualClock;
        if (clock instanceof ManualClock mc) {
            mc.addListener(this::clockChanged);
        }
    }

    public CronScheduler(Clock clock) {
        this(clock, 4);
    }

    public void addListener(SchedulerListener l) {
        listeners.add(Objects.requireNonNull(l, "listener"));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the dispatcher thread. Jobs may be added before or after. */
    public void start() {
        lock.lock();
        try {
            if (stopping) {
                throw new IllegalStateException("scheduler is shut down");
            }
            if (started) {
                return;
            }
            started = true;
            dispatcher = new Thread(this::dispatchLoop, "cron-dispatcher");
            dispatcher.setDaemon(true);
            dispatcher.start();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Graceful shutdown: no new dispatches, wait up to {@code grace} for dispatched runs to finish, then interrupt
     * whatever is left. Returns true if everything finished within the grace period. Safe to call twice.
     */
    public boolean shutdown(Duration grace) {
        Objects.requireNonNull(grace, "grace");
        Thread d;
        lock.lock();
        try {
            stopping = true;
            settled = false;
            wakeup.signalAll();
            d = dispatcher;
        } finally {
            lock.unlock();
        }
        try {
            if (d != null) {
                d.join();
            }
            workers.shutdown();
            if (workers.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Runnable r : workers.shutdownNow()) {
            if (r instanceof RunTask t) {
                lock.lock();
                try {
                    t.entry.inFlight--;
                    totalInFlight--;
                    idle.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ job management

    /** Adds a job. Throws if the id is already in use or the scheduler is shut down. */
    public void schedule(JobSpec spec) {
        Objects.requireNonNull(spec, "spec");
        lock.lock();
        try {
            if (stopping) {
                throw new IllegalStateException("scheduler is shut down");
            }
            if (entries.containsKey(spec.id())) {
                throw new IllegalArgumentException("duplicate job id: " + spec.id());
            }
            Entry e = new Entry(spec);
            scheduleNext(e, clock.instant());
            entries.put(spec.id(), e);
            changed();
        } finally {
            lock.unlock();
        }
    }

    /** Removes the job. A run that is already dispatched finishes. Returns false if the id is unknown. */
    public boolean cancel(String id) {
        lock.lock();
        try {
            Entry e = entries.remove(id);
            if (e == null) {
                return false;
            }
            e.cancelled = true;
            changed();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Stops firing the job. Returns false if the id is unknown. */
    public boolean pause(String id) {
        lock.lock();
        try {
            Entry e = entries.get(id);
            if (e == null) {
                return false;
            }
            e.paused = true;
            changed();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Resumes a paused job; the next fire is the first cron instant after now. Returns false if unknown. */
    public boolean resume(String id) {
        lock.lock();
        try {
            Entry e = entries.get(id);
            if (e == null) {
                return false;
            }
            if (e.paused) {
                e.paused = false;
                scheduleNext(e, clock.instant());
                changed();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Optional<JobStatus> status(String id) {
        lock.lock();
        try {
            Entry e = entries.get(id);
            return e == null ? Optional.empty() : Optional.of(snapshot(e));
        } finally {
            lock.unlock();
        }
    }

    /** All jobs in the order they were added. */
    public List<JobStatus> statuses() {
        lock.lock();
        try {
            List<JobStatus> out = new ArrayList<>();
            for (Entry e : entries.values()) {
                out.add(snapshot(e));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** The earliest instant (nominal plus jitter) at which any active job becomes due, if any. */
    public Optional<Instant> nextDueInstant() {
        lock.lock();
        try {
            return Optional.ofNullable(earliestDue());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the dispatcher has processed everything due at the current clock reading AND all dispatched runs
     * have finished. This is a condition wait, not a sleep; the timeout only guards against a hung test. Returns
     * false on timeout (or if the scheduler was never started).
     */
    public boolean awaitIdle(Duration timeout) {
        return await(timeout, true);
    }

    /** Like {@link #awaitIdle} but does not wait for dispatched runs to finish (useful with blocking jobs). */
    public boolean awaitDispatched(Duration timeout) {
        return await(timeout, false);
    }

    private boolean await(Duration timeout, boolean includeWorkers) {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (!(settled && (!includeWorkers || totalInFlight == 0))) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ dispatcher

    private void clockChanged() {
        lock.lock();
        try {
            changed();
        } finally {
            lock.unlock();
        }
    }

    /** Lock held. Marks "something happened the dispatcher has not seen" and wakes it. */
    private void changed() {
        settled = false;
        wakeup.signalAll();
    }

    private void dispatchLoop() {
        lock.lock();
        try {
            while (!stopping) {
                try {
                    dispatchPass();
                } catch (RuntimeException ex) {
                    System.err.println("cron-dispatcher: unexpected error in dispatch pass: " + ex);
                }
                settled = true;
                idle.signalAll();
                if (stopping) {
                    break;
                }
                try {
                    if (simulated) {
                        wakeup.await();
                    } else {
                        wakeup.awaitNanos(untilNextDueNanos());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    stopping = true;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private long untilNextDueNanos() {
        Instant due = earliestDue();
        if (due == null) {
            return MAX_REAL_WAIT_NANOS;
        }
        Duration d = Duration.between(clock.instant(), due);
        if (d.compareTo(Duration.ofNanos(MAX_REAL_WAIT_NANOS)) > 0) {
            return MAX_REAL_WAIT_NANOS;
        }
        return Math.max(1L, d.toNanos());
    }

    private Instant earliestDue() {
        Instant min = null;
        for (Entry e : entries.values()) {
            if (e.paused || e.nextDue == null) {
                continue;
            }
            if (min == null || e.nextDue.isBefore(min)) {
                min = e.nextDue;
            }
        }
        return min;
    }

    private void dispatchPass() {
        Instant now = clock.instant();
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.paused || e.cancelled || e.nextDue == null) {
                continue;
            }
            processEntry(e, now);
        }
    }

    private void scheduleNext(Entry e, Instant after) {
        Optional<java.time.ZonedDateTime> n = e.spec.cron().nextFire(after, e.spec.zone());
        if (n.isEmpty()) {
            e.nextNominal = null;
            e.nextDue = null;
            return;
        }
        e.nextNominal = n.get().toInstant();
        long jitterMs = e.spec.maxJitter().isZero() ? 0 : e.jitter.nextLong(e.spec.maxJitter().toMillis());
        e.nextDue = e.nextNominal.plusMillis(jitterMs);
    }

    private void processEntry(Entry e, Instant now) {
        JobSpec s = e.spec;
        List<Instant> onTime = new ArrayList<>();
        ArrayDeque<Instant> lateTail = new ArrayDeque<>();
        int tailCap = s.misfirePolicy() == MisfirePolicy.FIRE_ALL ? s.maxCatchUp() : 1;
        long lateCount = 0;
        while (e.nextDue != null && !e.nextDue.isAfter(now)) {
            Instant nominal = e.nextNominal;
            boolean late = Duration.between(e.nextDue, now).compareTo(s.misfireThreshold()) > 0;
            if (late) {
                lateCount++;
                lateTail.addLast(nominal);
                if (lateTail.size() > tailCap) {
                    lateTail.removeFirst();
                }
            } else {
                onTime.add(nominal);
            }
            scheduleNext(e, nominal);
        }
        long skipped = 0;
        switch (s.misfirePolicy()) {
            case SKIP -> skipped = lateCount;
            case FIRE_ONCE_NOW -> {
                if (lateCount > 0 && onTime.isEmpty()) {
                    fire(e, lateTail.peekLast(), true, (int) (lateCount - 1));
                    skipped = lateCount - 1;
                } else {
                    skipped = lateCount;
                }
            }
            case FIRE_ALL -> {
                for (Instant t : lateTail) {
                    fire(e, t, true, 0);
                }
                skipped = lateCount - lateTail.size();
            }
        }
        for (Instant t : onTime) {
            fire(e, t, false, 0);
        }
        if (skipped > 0) {
            e.misfireSkipped += skipped;
            notifySkipped(e, SkipReason.MISFIRE, (int) skipped, lastLateNominal(lateTail, e));
        }
    }

    private static Instant lastLateNominal(ArrayDeque<Instant> lateTail, Entry e) {
        Instant last = lateTail.peekLast();
        return last != null ? last : e.nextNominal;
    }

    private void fire(Entry e, Instant scheduledFor, boolean catchUp, int coalesced) {
        if (e.spec.noOverlap() && e.inFlight > 0) {
            e.overlapSkipped++;
            notifySkipped(e, SkipReason.OVERLAP, 1, scheduledFor);
            return;
        }
        FireContext ctx = new FireContext(e.spec.id(), scheduledFor, clock.instant(), catchUp, coalesced);
        e.inFlight++;
        totalInFlight++;
        e.fired++;
        for (SchedulerListener l : listeners) {
            try {
                l.onFire(ctx);
            } catch (RuntimeException ignored) {
                // a listener must not break dispatching
            }
        }
        try {
            workers.execute(new RunTask(e, ctx));
        } catch (RejectedExecutionException rejected) {
            e.inFlight--;
            totalInFlight--;
            e.fired--;
        }
    }

    private void notifySkipped(Entry e, SkipReason reason, int count, Instant last) {
        for (SchedulerListener l : listeners) {
            try {
                l.onSkipped(e.spec.id(), reason, count, last);
            } catch (RuntimeException ignored) {
                // a listener must not break dispatching
            }
        }
    }

    private void runJob(Entry e, FireContext ctx) {
        Throwable failure = null;
        try {
            e.spec.job().run(ctx);
        } catch (Throwable t) {
            failure = t;
        }
        for (SchedulerListener l : listeners) {
            try {
                l.onComplete(ctx, failure);
            } catch (RuntimeException ignored) {
                // a listener must not break the worker
            }
        }
        lock.lock();
        try {
            e.inFlight--;
            totalInFlight--;
            if (failure == null) {
                e.succeeded++;
            } else {
                e.failed++;
                e.lastError = failure.toString();
            }
            idle.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private JobStatus snapshot(Entry e) {
        return new JobStatus(e.spec.id(), e.paused, e.fired, e.succeeded, e.failed, e.misfireSkipped,
                e.overlapSkipped, e.inFlight, e.lastError, e.nextNominal);
    }
}
