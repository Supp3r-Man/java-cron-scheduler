# java-cron-scheduler

A cron expression parser with DST-aware next-fire-time computation, plus a small in-process job scheduler, in
Java 17. It exists to show how the hard parts of cron work (day-of-month vs day-of-week, month ends, leap years,
daylight-saving gaps and repeats, misfires, overlap, failure isolation) and how to test them without ever sleeping.
It is a learning/showcase project, not a replacement for Quartz, cron-utils or Spring's scheduler. The only
dependency is JUnit 5 (test scope); there are no runtime dependencies.

What it does:

- Parses 5-field (`minute hour day-of-month month day-of-week`) and 6-field (leading `second`) expressions with
  `*`, lists, ranges, steps (`*/15`, `5/20`, `10-30/10`), month and weekday names, plus `?`, `L` and `nL` (documented below).
- Computes the next fire time after an instant, in any `ZoneId`, with an explicit and tested DST policy.
- Runs jobs with one dispatcher thread and a fixed worker pool, an injected `java.time.Clock`, misfire policies,
  per-job no-overlap, failure isolation, cancel/pause/resume, seeded jitter and graceful shutdown.
- Ships a `ManualClock` so tests and the demo simulate days of schedule in milliseconds.

## How to run

```bash
mvn -q -B verify                                                      # compile + all tests + jar
java -cp target/classes com.github.sup3rmar10.cronscheduler.Demo      # simulated week across a DST change
java -jar target/java-cron-scheduler-0.1.0.jar                        # same demo, from the jar
python scripts/mutation_check.py                                      # mutation check (set MVN if mvn is not on PATH)
```

Library use:

```java
CronExpression e = CronExpression.parse("0 30 2 * * MON-FRI");           // 6 fields: second 0, 02:30 on weekdays
Optional<ZonedDateTime> next = e.nextFire(Instant.now(), ZoneId.of("America/New_York"));

ManualClock clock = new ManualClock(Instant.parse("2026-03-07T05:00:00Z"));
try (CronScheduler s = new CronScheduler(clock, 2)) {
    s.schedule(JobSpec.builder("report", CronExpression.parse("*/5 * * * *"), ctx -> System.out.println(ctx))
            .zone(ZoneId.of("America/New_York")).misfirePolicy(MisfirePolicy.FIRE_ONCE_NOW).noOverlap(true).build());
    s.start();
    clock.advance(Duration.ofMinutes(5));       // simulated time moves; the dispatcher wakes up on the notification
    s.awaitIdle(Duration.ofSeconds(5));         // condition wait, not a sleep
}
```

## Cron syntax

| Element | Meaning |
|---|---|
| `*` | every value of the field |
| `a`, `a-b`, `a,b,c` | single value, inclusive range, list (items can be mixed) |
| `*/n`, `a-b/n`, `a/n` | step; `a/n` runs from `a` to the field maximum. A step must be between 1 and the field size |
| `JAN-DEC`, `SUN-SAT` | names, case-insensitive, usable in lists and ranges (`FRI-SUN` works) |
| day-of-week `0-7` | 0 and 7 are both Sunday |
| `?` | day-of-month or day-of-week only; identical to `*` |
| `L` | day-of-month only: the last day of the month (may be listed: `L,15`) |
| `nL` | day-of-week only: the last weekday `n` of the month (`5L` = last Friday) |

Not supported, and rejected with a `CronParseException`: wrap-around ranges (`22-2`), `W`, `#`, `L-3`, `@daily`-style
macros, a year field. Five fields fire at second 0.

## Design notes

### Day-of-month versus day-of-week

A day field is a **wildcard only if its text is exactly `*` or `?`**.

- If at least one of the two fields is a wildcard, a day matches when the other one matches.
- If **both** are restricted, the rule is chosen at parse time: `DayRule.OR` (the default, classic Vixie cron:
  match if either matches) or `DayRule.AND` (match only if both do).

So `0 0 13 * FRI` means "the 13th and every Friday" by default and "Friday the 13th" with
`CronExpression.parse(expr, DayRule.AND)`. Note that `*/2` is deliberately not a wildcard here: `0 0 */2 * MON` under OR
means "odd days of the month, and Mondays". Some implementations treat any field starting with `*` as a wildcard; this
one does not, and a test pins the difference.

### Daylight-saving policy (literal wall clock)

One rule: **a fire time is any instant whose local wall-clock reading in the zone matches the expression.**
Two consequences, both tested for New York, London and Lord Howe (a 30-minute shift):

| Situation | What happens | Example |
|---|---|---|
| Spring-forward gap (local time does not exist) | skipped for that day, not shifted | `30 2 * * *` in New York has no run on 2026-03-08 |
| Fall-back overlap (local time happens twice) | fires twice, once per pass | `30 1 * * *` fires at 01:30 EDT and again at 01:30 EST on 2026-11-01 |

Why this policy: it is the simplest rule that is fully defined, it is what the brute-force oracle checks, and it
needs no special cases for 30-minute shifts. Its cost is real and worth knowing: a daily job in the overlap hour runs
twice on one day and a daily job in the gap hour is skipped once a year. Frequent schedules behave naturally
(`0 * * * *` has 23 runs on the spring day and 25 on the fall day; `*/15` has 92 and 100). If exactly-once-per-day
matters, schedule outside the 01:00-03:00 local window, schedule in UTC, or make the job idempotent. A policy switch
(for example "gap runs at the end of the gap, overlap runs once") is a possible extension, not implemented.

How it is computed: the timeline is cut into segments of constant UTC offset using `ZoneRules.nextTransition`.
Inside a segment the next matching local date-time is found with plain calendar arithmetic and converted with that
segment's offset; a candidate at or after the segment's end is discarded and the search continues in the next
segment. Gaps vanish (no instant has that wall clock) and overlaps repeat (two segments contain it) without any
special-casing. Month ends and leap years come from `LocalDate.lengthOfMonth()`, so `0 0 29 2 *` correctly jumps from
2096 to 2104 (2100 is not a leap year). Search horizon is 50 years: an expression with no match in that window, such as
February 30, returns `Optional.empty()`.

### Scheduler

One dispatcher thread owns all scheduling decisions; a fixed `ThreadPoolExecutor` runs the jobs (daemon threads).

- **Clock**: all time comes from the injected `Clock`. With a `ManualClock` the dispatcher sleeps on a condition and the
  clock calls back synchronously on every change, so `awaitIdle()` ("dispatcher has seen the new time and all
  dispatched runs have finished") is reliable and tests never sleep. With any other clock it waits until the next due
  instant (at most 30 s per wait).
- **First fire**: a job added at time T first fires at the first cron instant strictly after T.
- **Misfires**: an occurrence more than `misfireThreshold` (default 1 s, strict) late is a misfire; jitter is not counted
  as lateness. `FIRE_ONCE_NOW` (default) runs once now and counts the older ones as coalesced (if an on-time occurrence
  exists in the same pass, that run already covers "now"); `SKIP` drops late occurrences; `FIRE_ALL` runs them in order
  but at most `maxCatchUp` (the most recent ones; older ones are counted as skipped).
- **No-overlap**: an occurrence is skipped (not queued) while the previous run is queued or running. With `FIRE_ALL` this
  means only the first catch-up of a pass is dispatched.
- **Failure isolation**: a job that throws (even an `Error`) is recorded (`failed`, `lastError`), keeps its schedule and
  affects nothing else. Listener exceptions are swallowed.
- **Pause/resume/cancel**: resume recomputes the next fire from "now", so paused time is never caught up. Cancel removes
  the job; a run already dispatched finishes.
- **Jitter**: `jitter(max, seed)` delays each run by the k-th value of `new Random(seed).nextLong(max.toMillis())`:
  reproducible, never early, below `max`.
- **Shutdown**: stops dispatching (due-but-undispatched occurrences are dropped), waits up to the grace period for
  dispatched runs, then interrupts the rest; returns whether everything finished in time.

## Demo output (real run)

`Demo` simulates 2026-03-07 to 2026-03-14 in America/New_York (clocks jump 02:00 EST to 03:00 EDT on the 8th) with five
jobs on one worker thread, then a 5.5 hour stall under each misfire policy. Abridged:

```
== 1. A simulated week in America/New_York, 2026-03-07T00:00 to 2026-03-14T00:00
DST change: local 2026-03-08T02:00 -05:00 becomes 2026-03-08T03:00 -04:00 (the local hour in between does not exist)
Sat 2026-03-07 02:30:00 EST  nightly-report   scheduled for 02:30
Sat 2026-03-07 08:01:40 EST  jittered-warmup  scheduled for 08:00
Sun 2026-03-08 00:00:00 EST  six-hourly       scheduled for 00:00
Sun 2026-03-08 06:00:00 EDT  six-hourly       scheduled for 06:00      <- 5 elapsed hours after 00:00, 6 on the wall clock
Mon 2026-03-09 02:30:00 EDT  nightly-report   scheduled for 02:30      <- Sunday 02:30 did not exist, so no run on the 8th
Tue 2026-03-10 12:15:00 EDT  flaky-noon       scheduled for 12:15
      ! flaky-noon failed: simulated failure on the 10th (other jobs unaffected)
...
Summary (nightly-report is 02:30 local: it has no run on 2026-03-08 because 02:30 did not exist):
  nightly-report   fired=6 succeeded=6 failed=0
  six-hourly       fired=27 succeeded=27 failed=0
  weekday-9am      fired=5 succeeded=5 failed=0
  flaky-noon       fired=7 succeeded=6 failed=1
  jittered-warmup  fired=7 succeeded=7 failed=0

== 2. A 5.5 hour stall on an hourly job under each misfire policy (UTC)
Occurrences 01:00..05:00 all became due while 'stalled'; the clock then jumps to 05:30.
  run   fire-once-now    scheduled for 05:00 catchUp=true coalesced=4
  skip  fire-once-now    4 occurrence(s), reason MISFIRE
  skip  skip             5 occurrence(s), reason MISFIRE
  run   fire-all-cap-3   scheduled for 03:00 catchUp=true coalesced=0
  run   fire-all-cap-3   scheduled for 04:00 catchUp=true coalesced=0
  run   fire-all-cap-3   scheduled for 05:00 catchUp=true coalesced=0
  skip  fire-all-cap-3   2 occurrence(s), reason MISFIRE
```

(The jitter offsets come from the fixed seed 42, so they repeat on every run. The demo output is also asserted by tests.)

## Tests

180 test executions, all passing (`mvn -q -B verify` on JDK 17, Windows): `BruteForcePropertyTest` 6, `CronParserTest` 64 (one parameterised method runs over 40 malformed expressions), `CronSchedulerTest` 51, `DstTest` 18, `NextFireTest` 31, `SupportTypesTest` 10.

- **Brute-force property test.** About 5,950 seeded random cases compare `nextFire` with a brute-force scanner that walks forward from the start instant and tests every candidate against value sets built independently of the parser: 2,400 five-field, 1,200 six-field, 250 sparse-date, 1,500 DST-focused and 600 DST-focused six-field cases, over America/New_York, Europe/London, Australia/Lord_Howe and Asia/Kolkata. It found no mismatch on its first full run. The test also checks that the generators produce real matches, with thresholds I lowered after measuring them (see the limits below).
- **No sleeping.** Scheduler tests use `ManualClock`, so they do not wait on the wall clock. Each test has a 10 s timeout. `CronSchedulerTest` took about 3 s on one run and about 9.7 s on another with no failure, so on a much slower machine it could hit that limit; I did not test one.

## Mutation check

Passing tests prove little if they would also pass on broken code, so I applied 25 behaviour-defining mutations one at a time (single textual replacements run by `scripts/mutation_check.py`, which requires each match text to occur exactly once and restores the file afterwards), ran `mvn test` for each, and recorded whether any test failed. This is a hand-rolled check, not PIT, and the mutations are the ones I thought of, so it says nothing about bugs I did not think of.

**Result: 25 of 25 killed, no survivors.** 23 were killed by a failing test; 2 (M07, M11) were killed by timeout (see notes).

| # | Mutation | Result | First failing test(s) |
|---|---|---|---|
| M01 | day-of-month/day-of-week: OR becomes AND | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 5 more |
| M02 | wildcard day field no longer forces AND (wildcard + OR behaves as OR) | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 19 more |
| M03 | DayRule.AND is ignored (always OR unless a wildcard is present) | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 5 more |
| M04 | L in day-of-month means the day before the last | killed | `NextFireTest.lastDayOfMonthCombinesWithOtherDaysInAList`, `NextFireTest.lastDayOfMonthTracksMonthLengthAndLeapYears` |
| M05 | nL window one day too narrow (dom + 7 > len becomes dom + 6 > len) | killed | `NextFireTest.lastSundayCanBeSixDaysBeforeMonthEnd` (only this one) |
| M06 | nL window one day too wide (dom + 7 > len becomes dom + 8 > len) | killed | `NextFireTest.lastSaturdayIsNotTheSaturdayAWeekBeforeTheLast` (only this one) |
| M07 | non-matching month skips two months instead of one | killed by timeout | no test failed; the search never terminates (see notes) |
| M08 | hour not reset to 0 when moving to the next day | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 11 more |
| M09 | minute lower bound ignores whether the hour is the starting hour | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 10 more |
| M10 | second lower bound ignores whether the minute is the starting minute | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 65 more |
| M11 | next fire is not strictly after (an exact match is returned again) | killed by timeout | no test failed; the search never terminates (see notes) |
| M12 | candidate exactly at a DST transition instant is accepted for the old offset | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 1 more |
| M13 | search after a transition starts one second late (misses a fire at the transition instant) | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 8 more |
| M14 | day-of-week 7 is not folded onto Sunday (0) | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.expressionsAimedAtTheSmallHoursNearDstChangesMatchTheBruteForceScanner`, 5 more |
| M15 | FRI-SUN style ranges (ending in 0/SUN) are rejected as reversed | killed | `CronParserTest.aDayOfWeekRangeMayEndInSunday` (only this one) |
| M16 | step larger than the field is accepted | killed | `CronParserTest.malformedExpressionsAreRejected` (only this one) |
| M17 | day-of-week star/start-step runs to 7 instead of 6 | killed | `BruteForcePropertyTest.dstFocusedSixFieldExpressionsMatchTheBruteForceScanner`, `BruteForcePropertyTest.fiveFieldExpressionsMatchTheBruteForceScanner`, 3 more |
| M18 | late threshold is inclusive (exactly-at-threshold counts as a misfire) | killed | `CronSchedulerTest.anOccurrenceExactlyAtTheThresholdIsStillOnTime` (only this one) |
| M19 | no-overlap is never enforced | killed | `CronSchedulerTest.listenerIsToldAboutMisfireAndOverlapSkips`, `CronSchedulerTest.noOverlapSkipsWhilePreviousRunIsInFlight`, 1 more |
| M20 | FIRE_ONCE_NOW also runs a catch-up when an on-time run exists | killed | `CronSchedulerTest.fireOnceNowDoesNotRunTwiceWhenAnOnTimeOccurrenceExists` (only this one) |
| M21 | FIRE_ALL cap is off by one | killed | `CronSchedulerTest.fireAllRunsLateOnesFirstAndTheOnTimeOneLast`, `CronSchedulerTest.fireAllRunsTheMostRecentOnesUpToTheCapInOrder`, 1 more |
| M22 | resume does not recompute the next fire (paused time is caught up) | killed | `CronSchedulerTest.pauseStopsFiringAndResumeDoesNotCatchUp` (only this one) |
| M23 | jitter is computed but never applied | killed | `CronSchedulerTest.jitterDelaysTheDueTimeButNotTheNominalOrTheMisfireClock`, `CronSchedulerTest.jitterIsReproduciblePerSeedAndDiffersBetweenSeeds` |
| M24 | cancel does not remove the job | killed | `CronSchedulerTest.aCancelledIdCanBeReused`, `CronSchedulerTest.cancelStopsFurtherRuns` |
| M25 | shutdown always reports a clean finish and never interrupts | killed | `CronSchedulerTest.shutdownWithNoGraceInterruptsRunningJobs` (only this one) |

The script lists at most the first two failing tests per mutation, so "first failing test(s)" is not the full list.

Notes on the result (a clean 25 of 25 deserves some suspicion, so here are the caveats):

- **M07 and M11 are non-terminating mutants.** Both break the forward search so it never makes progress. A CPU-bound loop is not interrupted by the 10 s per-test timeout, so these runs hit the script's own 15 minute cap and are counted as "killed by timeout", with no failing test named. That is a weaker kind of kill than an assertion: it proves the suite does not pass, not that a specific test pins the behaviour. Also, on Windows the timed-out Maven run left its test JVM spinning and holding the output pipe, so the run did not move on by itself; I ended that JVM by hand during M11. M11's result line is the script's own timeout result, and I did not re-run it.
- **Most of the date-arithmetic mutations are killed by the brute-force property test**, not by a hand-written case (for example M01 to M03, M08 to M10, M13, M14 and M17). That is the point of the property test, but it also means those mutations are only as well covered as the scanner's random expressions and instants.
- **Mutations killed by exactly one test** are the ones I would worry about if that test were refactored: M05, M06, M15, M16, M18, M20, M22, M25.
- The mutations cover day-rule semantics, field carry, DST handling, parsing, misfire policies, no-overlap, pause/resume, jitter, cancel and shutdown. No mutation targets the listener callbacks, `JobStatus` reporting or the `Demo` output.
- Every test class ran against each mutant, so the 10 s per-test timeout (`junit-platform.properties`) also turns plain hangs in the scheduler tests into kills. The log cannot say which kills were hangs and which were assertions, except for the two timeout rows.
- I did not add any test because of this run: nothing survived. The mutation list was written by the same author as the tests, so the check is an audit of the tests I wrote, not an independent one.

Reproduce: `python scripts/mutation_check.py` (set `MVN` to the full path of `mvn.cmd` if `mvn` is not on PATH; pass mutation ids such as `M05` to run just those). Expect an hour or more: most mutants take one to a few minutes, and each of M07 and M11 waits out the 15 minute cap. I did not time the whole run.

## Limits / not verified

- Not compared against Quartz, cron-utils, Spring's `CronExpression` or Vixie cron. The semantics here are the ones
  documented above, checked against java.time and a brute-force scanner I wrote; where those tools differ from this one
  (DST handling in particular) I have not tested how.
- DST behaviour was checked for America/New_York, Europe/London and Australia/Lord_Howe on the 2026 transitions and by
  random instants in 2025-2029 in four zones (those three plus Asia/Kolkata). Historical zone changes, zones that skip
  a whole calendar day, and negative-DST zones were not tested. Results depend on the JDK's tzdata.
- The literal DST policy duplicates or skips daily jobs in the change hour (see above); there is no switch to change it.
- The brute-force scanner looks 2 days ahead (3 for the DST-focused cases, 45 for the sparse ones), so a bug that only
  shows for fire times far from the start instant is covered by the hand-written month-end/leap-year tests, not the
  property tests. The oracle and the implementation share one author and one reading of the grammar.
- Parser limits: no wrap-around ranges, no `W`/`#`/`L-n`, no macros, no year field, `L`/`nL` are not combined with steps.
- The scheduler is in-memory and single-process: no persistence, no clustering, no recovery after a restart. There is no
  per-job timeout (a hung job holds a worker until shutdown interrupts it). A real-clock run (system clock, real waits)
  is only lightly exercised: the dispatcher's timed wait path is not covered by the unit tests, which all use `ManualClock`.
- Very large clock jumps on very frequent jobs are processed occurrence by occurrence (a per-second job and a one-day jump
  is 86 400 iterations, tested; a one-year jump would be about 31 million and slow).
- A clock that goes backwards is not supported (`ManualClock` refuses; with a real clock the job simply waits to catch up).
- CI: the workflow is included. CI ran green on the first run on GitHub Actions (Java 17 and 21, ubuntu-latest, 2026-10-20). (It runs `mvn -q -B verify` and the demo on Java 17 and 21.)

## Layout

```
src/main/java/com/github/sup3rmar10/cronscheduler/   CronExpression (parser + next fire), CronScheduler, JobSpec, ManualClock, Demo, ...
src/test/java/...                                    unit tests, DST tests, brute-force property test, scheduler tests
scripts/mutation_check.py                            the mutation check described above
```

Clone: `git clone https://github.com/Supp3r-Man/java-cron-scheduler.git`

MIT licensed.
