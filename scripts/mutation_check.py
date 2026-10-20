#!/usr/bin/env python
"""Hand-rolled mutation check: apply one textual mutation at a time, run the unit tests,
record whether at least one test failed, then restore the original file.

Usage:  python scripts/mutation_check.py            (all mutations)
        python scripts/mutation_check.py M05 M10    (selected ids)
Set MVN to the Maven executable if `mvn` is not on PATH.

Each mutation replaces the single occurrence of `old` with `new`. If `old` does not occur exactly once in the
file the mutation is reported as BROKEN (the script is out of date), not as a survivor. A mutant that does not
compile is reported as COMPILE ERROR and does not count as killed. Every test method has a 10 s timeout
(src/test/resources/junit-platform.properties), so a mutant that loops forever fails a test instead of hanging;
the whole Maven run additionally has a 15 minute cap. Files are always restored from the original bytes,
even on Ctrl-C.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "github", "sup3rmar10", "cronscheduler")
MVN = os.environ.get("MVN", "mvn")

CE = "CronExpression.java"
CS = "CronScheduler.java"
MC = "ManualClock.java"

MUTATIONS = [
    ("M01", "day-of-month/day-of-week: OR becomes AND", CE,
     "return domOk || dowOk;", "return domOk && dowOk;"),
    ("M02", "wildcard day field no longer forces AND (wildcard + OR behaves as OR)", CE,
     "if (dayRule == DayRule.AND || domWildcard || dowWildcard) {", "if (dayRule == DayRule.AND) {"),
    ("M03", "DayRule.AND is ignored (always OR unless a wildcard is present)", CE,
     "if (dayRule == DayRule.AND || domWildcard || dowWildcard) {", "if (domWildcard || dowWildcard) {"),
    ("M04", "L in day-of-month means the day before the last", CE,
     "(lastDayOfMonth && dom == len)", "(lastDayOfMonth && dom == len - 1)"),
    ("M05", "nL window one day too narrow (dom + 7 > len becomes dom + 6 > len)", CE,
     "dom + 7 > len", "dom + 6 > len"),
    ("M06", "nL window one day too wide (dom + 7 > len becomes dom + 8 > len)", CE,
     "dom + 7 > len", "dom + 8 > len"),
    ("M07", "non-matching month skips two months instead of one", CE,
     "d = d.withDayOfMonth(1).plusMonths(1);", "d = d.withDayOfMonth(1).plusMonths(2);"),
    ("M08", "hour not reset to 0 when moving to the next day", CE,
     "h = 0;\n            m = 0;\n            s = 0;", "m = 0;\n            s = 0;"),
    ("M09", "minute lower bound ignores whether the hour is the starting hour", CE,
     "int mFrom = hh == h ? m : 0;", "int mFrom = m;"),
    ("M10", "second lower bound ignores whether the minute is the starting minute", CE,
     "int sFrom = (hh == h && mm == m) ? s : 0;", "int sFrom = s;"),
    ("M11", "next fire is not strictly after (an exact match is returned again)", CE,
     "long start = after.getEpochSecond() + 1;", "long start = after.getEpochSecond();"),
    ("M12", "candidate exactly at a DST transition instant is accepted for the old offset", CE,
     "candEpoch < next.getInstant().getEpochSecond()", "candEpoch <= next.getInstant().getEpochSecond()"),
    ("M13", "search after a transition starts one second late (misses a fire at the transition instant)", CE,
     "start = next.getInstant().getEpochSecond();", "start = next.getInstant().getEpochSecond() + 1;"),
    ("M14", "day-of-week 7 is not folded onto Sunday (0)", CE,
     "p.bits.set(f == CronField.DAY_OF_WEEK ? v % 7 : v);", "p.bits.set(v);"),
    ("M15", "FRI-SUN style ranges (ending in 0/SUN) are rejected as reversed", CE,
     "if (f == CronField.DAY_OF_WEEK && hi == 0 && lo > 0) {", "if (false) {"),
    ("M16", "step larger than the field is accepted", CE,
     "if (step < 1 || step > f.span()) {", "if (step < 1) {"),
    ("M17", "day-of-week star/start-step runs to 7 instead of 6", CE,
     "int upper = f == CronField.DAY_OF_WEEK ? 6 : f.max();", "int upper = f.max();"),
    ("M18", "late threshold is inclusive (exactly-at-threshold counts as a misfire)", CS,
     "Duration.between(e.nextDue, now).compareTo(s.misfireThreshold()) > 0", "Duration.between(e.nextDue, now).compareTo(s.misfireThreshold()) >= 0"),
    ("M19", "no-overlap is never enforced", CS,
     "if (e.spec.noOverlap() && e.inFlight > 0) {", "if (false) {"),
    ("M20", "FIRE_ONCE_NOW also runs a catch-up when an on-time run exists", CS,
     "if (lateCount > 0 && onTime.isEmpty()) {", "if (lateCount > 0) {"),
    ("M21", "FIRE_ALL cap is off by one", CS,
     "s.maxCatchUp() : 1;", "s.maxCatchUp() + 1 : 1;"),
    ("M22", "resume does not recompute the next fire (paused time is caught up)", CS,
     "e.paused = false;\n                scheduleNext(e, clock.instant());", "e.paused = false;"),
    ("M23", "jitter is computed but never applied", CS,
     "e.nextDue = e.nextNominal.plusMillis(jitterMs);", "e.nextDue = e.nextNominal;"),
    ("M24", "cancel does not remove the job", CS,
     "Entry e = entries.remove(id);", "Entry e = entries.get(id);"),
    ("M25", "shutdown always reports a clean finish and never interrupts", CS,
     "if (workers.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS)) {", "if (true) {"),
]


def run_tests():
    try:
        p = subprocess.run([MVN, "-B", "-q", "test"], cwd=ROOT, capture_output=True, text=True, timeout=900)
    except subprocess.TimeoutExpired:
        return "timeout", [], 0
    out = p.stdout + p.stderr
    if "COMPILATION ERROR" in out:
        return "compile-error", [], 0
    failing = sorted(set(re.findall(r"\[ERROR\]\s{2,}(\w+Test\.\w+)", out)))
    return ("killed" if p.returncode != 0 else "survived"), failing, len(failing)


def main():
    wanted = set(sys.argv[1:])
    originals = {}
    results = []
    try:
        for mid, desc, fname, old, new in MUTATIONS:
            if wanted and mid not in wanted:
                continue
            path = os.path.join(SRC, fname)
            if fname not in originals:
                with open(path, "rb") as f:
                    originals[fname] = f.read()
            text = originals[fname].decode("utf-8").replace("\r\n", "\n")
            if text.count(old) != 1:
                results.append((mid, desc, "BROKEN (pattern found %d times)" % text.count(old), []))
                print(f"{mid} | {desc} | BROKEN (pattern found {text.count(old)} times)", flush=True)
                continue
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text.replace(old, new, 1))
            try:
                status, failing, n = run_tests()
            finally:
                with open(path, "wb") as f:
                    f.write(originals[fname])
            label = {"killed": "KILLED", "survived": "SURVIVED", "compile-error": "COMPILE ERROR (invalid)",
                     "timeout": "KILLED (timeout)"}[status]
            results.append((mid, desc, label, failing))
            print(f"{mid} | {desc} | {label} | {n} failing: {', '.join(failing[:3])}", flush=True)
    finally:
        for fname, data in originals.items():
            with open(os.path.join(SRC, fname), "wb") as f:
                f.write(data)
    killed = sum(1 for r in results if r[2].startswith("KILLED"))
    print(f"\n{killed}/{len(results)} mutations killed")


if __name__ == "__main__":
    main()
