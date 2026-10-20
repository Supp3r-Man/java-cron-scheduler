package com.github.sup3rmar10.cronscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SupportTypesTest {
    private static final CronExpression EVERY_MINUTE = CronExpression.parse("* * * * *");

    // ------------------------------------------------------------------ JobSpec

    @Test
    void jobSpecDefaults() {
        JobSpec s = JobSpec.builder("a", EVERY_MINUTE, c -> { }).build();
        assertEquals("a", s.id());
        assertEquals(ZoneOffset.UTC, s.zone());
        assertEquals(MisfirePolicy.FIRE_ONCE_NOW, s.misfirePolicy());
        assertEquals(10, s.maxCatchUp());
        assertFalse(s.noOverlap());
        assertEquals(Duration.ofSeconds(1), s.misfireThreshold());
        assertEquals(Duration.ZERO, s.maxJitter());
    }

    @Test
    void jobSpecRejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> JobSpec.builder(" ", EVERY_MINUTE, c -> { }));
        assertThrows(IllegalArgumentException.class, () -> JobSpec.builder(null, EVERY_MINUTE, c -> { }));
        assertThrows(NullPointerException.class, () -> JobSpec.builder("a", null, c -> { }));
        assertThrows(NullPointerException.class, () -> JobSpec.builder("a", EVERY_MINUTE, null));
        JobSpec.Builder b = JobSpec.builder("a", EVERY_MINUTE, c -> { });
        assertThrows(IllegalArgumentException.class, () -> b.maxCatchUp(0));
        assertThrows(IllegalArgumentException.class, () -> b.misfireThreshold(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> b.jitter(Duration.ofNanos(10), 1));
        assertThrows(IllegalArgumentException.class, () -> b.jitter(Duration.ofSeconds(-1), 1));
    }

    @Test
    void jobSpecKeepsTheOptionsItWasGiven() {
        JobSpec s = JobSpec.builder("a", EVERY_MINUTE, c -> { }).zone(ZoneId.of("Europe/London")).noOverlap(true)
                .misfirePolicy(MisfirePolicy.SKIP).maxCatchUp(3).misfireThreshold(Duration.ofSeconds(5))
                .jitter(Duration.ofSeconds(9), 77).build();
        assertEquals(ZoneId.of("Europe/London"), s.zone());
        assertTrue(s.noOverlap());
        assertEquals(MisfirePolicy.SKIP, s.misfirePolicy());
        assertEquals(3, s.maxCatchUp());
        assertEquals(Duration.ofSeconds(5), s.misfireThreshold());
        assertEquals(Duration.ofSeconds(9), s.maxJitter());
        assertEquals(77, s.jitterSeed());
    }

    // ------------------------------------------------------------------ ManualClock

    @Test
    void manualClockAdvancesAndReportsItsZone() {
        ManualClock c = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        c.advance(Duration.ofHours(2));
        assertEquals(Instant.parse("2026-01-01T02:00:00Z"), c.instant());
        assertEquals(ZoneId.of("Asia/Tokyo"), c.getZone());
        assertEquals(ZoneOffset.UTC, new ManualClock(Instant.EPOCH).getZone());
    }

    @Test
    void manualClockRefusesToGoBackwards() {
        ManualClock c = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> c.advance(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> c.set(Instant.parse("2025-12-31T23:59:59Z")));
        c.set(Instant.parse("2026-01-01T00:00:00Z")); // same instant is fine
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), c.instant());
    }

    @Test
    void manualClockNotifiesListenersOnEveryChange() {
        ManualClock c = new ManualClock(Instant.EPOCH);
        AtomicInteger n = new AtomicInteger();
        c.addListener(n::incrementAndGet);
        c.advance(Duration.ofSeconds(1));
        c.set(Instant.EPOCH.plusSeconds(10));
        assertEquals(2, n.get());
    }

    @Test
    void manualClockWithZoneIsADetachedSnapshot() {
        ManualClock c = new ManualClock(Instant.EPOCH);
        Clock other = c.withZone(ZoneId.of("Asia/Tokyo"));
        c.advance(Duration.ofHours(1));
        assertEquals(Instant.EPOCH, other.instant());
        assertEquals(ZoneId.of("Asia/Tokyo"), other.getZone());
    }

    // ------------------------------------------------------------------ Demo

    private static String demoOutput() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Demo.run(ps);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void demoShowsTheSkippedRunOnTheSpringForwardDay() throws Exception {
        String out = demoOutput();
        assertTrue(out.contains("nightly-report   fired=6 succeeded=6 failed=0"), out);
        assertFalse(out.contains("Sun 2026-03-08 02:30"), out);
        assertTrue(out.contains("Mon 2026-03-09 02:30:00 EDT  nightly-report"), out);
        assertTrue(out.contains("flaky-noon       fired=7 succeeded=6 failed=1"), out);
    }

    @Test
    void demoShowsAllThreeMisfirePolicies() throws Exception {
        String out = demoOutput();
        assertTrue(out.contains("run   fire-once-now    scheduled for 05:00 catchUp=true coalesced=4"), out);
        assertTrue(out.contains("skip  skip             5 occurrence(s), reason MISFIRE"), out);
        assertTrue(out.contains("run   fire-all-cap-3   scheduled for 03:00"), out);
    }

    @Test
    void demoOutputIsDeterministic() throws Exception {
        assertEquals(demoOutput(), demoOutput());
        assertNotEquals("", demoOutput());
    }
}
