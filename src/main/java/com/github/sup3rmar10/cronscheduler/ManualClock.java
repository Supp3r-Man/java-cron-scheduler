package com.github.sup3rmar10.cronscheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A clock that only moves when told to. It is the "controllable test clock": tests and the demo advance it
 * explicitly, so nothing ever sleeps. {@link CronScheduler} recognises it and gets notified synchronously on
 * every change, which is what makes {@link CronScheduler#awaitIdle} reliable.
 *
 * <p>Time can only move forward; going backwards throws, because the scheduler has no defined behaviour for it.
 */
public final class ManualClock extends Clock {
    private final ZoneId zone;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Instant now;

    public ManualClock(Instant start, ZoneId zone) {
        this.now = Objects.requireNonNull(start, "start");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public ManualClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    /** Moves the clock forward by a non-negative duration and notifies listeners before returning. */
    public void advance(Duration d) {
        Objects.requireNonNull(d, "d");
        if (d.isNegative()) {
            throw new IllegalArgumentException("cannot move a ManualClock backwards: " + d);
        }
        synchronized (this) {
            now = now.plus(d);
        }
        fire();
    }

    /** Sets the clock to an instant that is not before the current one. */
    public void set(Instant t) {
        Objects.requireNonNull(t, "t");
        synchronized (this) {
            if (t.isBefore(now)) {
                throw new IllegalArgumentException("cannot move a ManualClock backwards: " + now + " -> " + t);
            }
            now = t;
        }
        fire();
    }

    /** Registers a callback that runs on the calling thread after each change. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void fire() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** Returns a detached fixed clock at the current instant; it does not follow later advances. */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
