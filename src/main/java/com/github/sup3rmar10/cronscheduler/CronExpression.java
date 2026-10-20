package com.github.sup3rmar10.cronscheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable, parsed cron expression plus the next-fire-time computation.
 *
 * <h2>Syntax</h2>
 * Five fields {@code minute hour day-of-month month day-of-week}, or six with a leading {@code second}
 * field (five-field expressions fire at second 0). Each field is a comma-separated list of items:
 * {@code *}, {@code a}, {@code a-b}, {@code *}{@code /n}, {@code a/n} (a to the field maximum, every n),
 * {@code a-b/n}. Month accepts {@code JAN-DEC}; day-of-week accepts {@code SUN-SAT} and the numbers 0-7
 * (0 and 7 are Sunday). Names are case-insensitive. Ranges must not be reversed (no wrap-around such as
 * {@code 22-2}); in day-of-week, a range may end in {@code 0}/{@code SUN} to mean 7, so {@code FRI-SUN} works.
 * A step must be between 1 and the size of the field.
 *
 * <p>Extras, supported and tested: {@code ?} (only in day-of-month and day-of-week, identical to {@code *}),
 * {@code L} in day-of-month (last day of the month; may appear in a list) and {@code nL} in day-of-week
 * (last weekday n of the month, e.g. {@code 5L} is the last Friday). Not supported: {@code W}, {@code #},
 * {@code L-n}, {@code @daily}-style macros and year fields.
 *
 * <h2>Day-of-month versus day-of-week</h2>
 * A field is a <em>wildcard</em> only if its text is exactly {@code *} or {@code ?}. If at least one of the two
 * is a wildcard, a day matches when the other one matches. If both are restricted, {@link DayRule#OR} (default)
 * matches when either does, {@link DayRule#AND} when both do. Note that {@code *}{@code /2} is NOT a wildcard here.
 *
 * <h2>Daylight-saving policy (literal wall clock)</h2>
 * A fire time is any instant whose local wall-clock reading in the given zone matches the expression.
 * <ul>
 *   <li>Spring-forward gap: a local time that does not exist (02:30 on the day clocks jump from 02:00 to 03:00)
 *       is skipped for that day; nothing is shifted to the end of the gap.</li>
 *   <li>Fall-back overlap: a local time that happens twice fires twice, once per pass, because each pass is a
 *       distinct instant with a matching wall clock.</li>
 * </ul>
 * Both are consequences of one rule, which is also what the brute-force oracle in the tests checks.
 *
 * <p>Fire times are searched at most {@value #MAX_SEARCH_YEARS} years ahead; an expression with no match in that
 * window (for example February 30) yields {@link Optional#empty()}.
 */
public final class CronExpression {
    static final int MAX_SEARCH_YEARS = 50;

    private static final List<String> MONTH_NAMES =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DOW_NAMES = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private final String source;
    private final DayRule dayRule;
    private final boolean hasSeconds;
    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek; // 0-6, Sunday = 0
    private final boolean lastDayOfMonth;
    private final BitSet lastDaysOfWeek; // 0-6
    private final boolean domWildcard;
    private final boolean dowWildcard;

    private CronExpression(String source, DayRule dayRule, boolean hasSeconds, Parsed sec, Parsed min, Parsed hour,
                           Parsed dom, Parsed mon, Parsed dow) {
        this.source = source;
        this.dayRule = dayRule;
        this.hasSeconds = hasSeconds;
        this.seconds = sec.bits;
        this.minutes = min.bits;
        this.hours = hour.bits;
        this.daysOfMonth = dom.bits;
        this.months = mon.bits;
        this.daysOfWeek = dow.bits;
        this.lastDayOfMonth = dom.lastDom;
        this.lastDaysOfWeek = dow.lastDow;
        this.domWildcard = dom.wildcard;
        this.dowWildcard = dow.wildcard;
    }

    public static CronExpression parse(String expression) {
        return parse(expression, DayRule.OR);
    }

    public static CronExpression parse(String expression, DayRule dayRule) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(dayRule, "dayRule");
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            throw new CronParseException("empty cron expression");
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 5 && parts.length != 6) {
            throw new CronParseException("expected 5 fields (minute hour day-of-month month day-of-week) or 6 with a "
                    + "leading seconds field, got " + parts.length + ": '" + trimmed + "'");
        }
        boolean withSeconds = parts.length == 6;
        int i = 0;
        Parsed sec;
        if (withSeconds) {
            sec = parseField(parts[i++], CronField.SECOND);
        } else {
            sec = new Parsed();
            sec.bits.set(0);
        }
        Parsed min = parseField(parts[i++], CronField.MINUTE);
        Parsed hour = parseField(parts[i++], CronField.HOUR);
        Parsed dom = parseField(parts[i++], CronField.DAY_OF_MONTH);
        Parsed mon = parseField(parts[i++], CronField.MONTH);
        Parsed dow = parseField(parts[i], CronField.DAY_OF_WEEK);
        return new CronExpression(String.join(" ", parts), dayRule, withSeconds, sec, min, hour, dom, mon, dow);
    }

    // ---------------------------------------------------------------- parsing

    private static final class Parsed {
        final BitSet bits = new BitSet();
        final BitSet lastDow = new BitSet();
        boolean lastDom;
        boolean wildcard;
    }

    private static Parsed parseField(String text, CronField f) {
        Parsed p = new Parsed();
        boolean dayField = f == CronField.DAY_OF_MONTH || f == CronField.DAY_OF_WEEK;
        if (text.equals("?")) {
            if (!dayField) {
                throw new CronParseException("'?' is only allowed in day-of-month and day-of-week, not in " + f.label());
            }
            p.bits.set(f.min(), (f == CronField.DAY_OF_WEEK ? 6 : f.max()) + 1);
            p.wildcard = true;
            return p;
        }
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new CronParseException(f.label() + ": empty list item in '" + text + "'");
            }
            parseItem(item, f, p);
        }
        p.wildcard = text.equals("*");
        return p;
    }

    private static void parseItem(String item, CronField f, Parsed p) {
        int slash = item.indexOf('/');
        String base = slash < 0 ? item : item.substring(0, slash);
        int step = 1;
        if (slash >= 0) {
            step = parseNumber(item.substring(slash + 1), f, "step");
            if (step < 1 || step > f.span()) {
                throw new CronParseException(f.label() + ": step must be between 1 and " + f.span() + " in '" + item + "'");
            }
        }
        int upper = f == CronField.DAY_OF_WEEK ? 6 : f.max();
        int lo;
        int hi;
        if (base.equals("*")) {
            lo = f.min();
            hi = upper;
        } else if (f == CronField.DAY_OF_MONTH && base.equals("L")) {
            if (slash >= 0) {
                throw new CronParseException("day-of-month: 'L' cannot have a step: '" + item + "'");
            }
            p.lastDom = true;
            return;
        } else if (f == CronField.DAY_OF_WEEK && base.length() == 2 && Character.toUpperCase(base.charAt(1)) == 'L'
                && Character.isDigit(base.charAt(0))) {
            if (slash >= 0) {
                throw new CronParseException("day-of-week: 'nL' cannot have a step: '" + item + "'");
            }
            p.lastDow.set(parseValue(base.substring(0, 1), f) % 7);
            return;
        } else if (base.indexOf('-') >= 0) {
            String[] ends = base.split("-", -1);
            if (ends.length != 2) {
                throw new CronParseException(f.label() + ": malformed range '" + item + "'");
            }
            lo = parseValue(ends[0], f);
            hi = parseValue(ends[1], f);
            if (f == CronField.DAY_OF_WEEK && hi == 0 && lo > 0) {
                hi = 7; // FRI-SUN
            }
        } else {
            lo = parseValue(base, f);
            hi = slash >= 0 ? upper : lo;
        }
        if (lo > hi) {
            throw new CronParseException(f.label() + ": reversed or empty range '" + item + "' (wrap-around is not supported)");
        }
        for (int v = lo; v <= hi; v += step) {
            p.bits.set(f == CronField.DAY_OF_WEEK ? v % 7 : v);
        }
    }

    private static int parseNumber(String tok, CronField f, String what) {
        if (!tok.matches("[0-9]{1,4}")) {
            throw new CronParseException(f.label() + ": invalid " + what + " '" + tok + "'");
        }
        return Integer.parseInt(tok);
    }

    private static int parseValue(String tok, CronField f) {
        int v;
        if (tok.matches("[0-9]{1,4}")) {
            v = Integer.parseInt(tok);
        } else if (f == CronField.MONTH && MONTH_NAMES.contains(tok.toUpperCase(Locale.ROOT))) {
            v = MONTH_NAMES.indexOf(tok.toUpperCase(Locale.ROOT)) + 1;
        } else if (f == CronField.DAY_OF_WEEK && DOW_NAMES.contains(tok.toUpperCase(Locale.ROOT))) {
            v = DOW_NAMES.indexOf(tok.toUpperCase(Locale.ROOT));
        } else {
            throw new CronParseException(f.label() + ": invalid value '" + tok + "'");
        }
        if (v < f.min() || v > f.max()) {
            throw new CronParseException(f.label() + ": " + v + " is out of range " + f.min() + "-" + f.max());
        }
        return v;
    }

    // ---------------------------------------------------------------- accessors

    /** The normalised text (trimmed, single spaces). */
    public String expression() {
        return source;
    }

    public boolean hasSeconds() {
        return hasSeconds;
    }

    public DayRule dayRule() {
        return dayRule;
    }

    /** The allowed values of a field. Day-of-week is 0-6 with Sunday = 0. Does not include L-style entries. */
    public Set<Integer> values(CronField field) {
        BitSet b = switch (field) {
            case SECOND -> seconds;
            case MINUTE -> minutes;
            case HOUR -> hours;
            case DAY_OF_MONTH -> daysOfMonth;
            case MONTH -> months;
            case DAY_OF_WEEK -> daysOfWeek;
        };
        Set<Integer> out = new TreeSet<>();
        b.stream().forEach(out::add);
        return out;
    }

    public boolean lastDayOfMonth() {
        return lastDayOfMonth;
    }

    /** Weekdays (0-6, Sunday = 0) given as {@code nL}. */
    public Set<Integer> lastDaysOfWeek() {
        Set<Integer> out = new TreeSet<>();
        lastDaysOfWeek.stream().forEach(out::add);
        return out;
    }

    // ---------------------------------------------------------------- matching

    /** True if the date satisfies month, day-of-month and day-of-week under this expression's {@link DayRule}. */
    boolean dayMatches(LocalDate d) {
        if (!months.get(d.getMonthValue())) {
            return false;
        }
        int dom = d.getDayOfMonth();
        int len = d.lengthOfMonth();
        boolean domOk = daysOfMonth.get(dom) || (lastDayOfMonth && dom == len);
        int dow = d.getDayOfWeek().getValue() % 7;
        boolean dowOk = daysOfWeek.get(dow) || (lastDaysOfWeek.get(dow) && dom + 7 > len);
        if (dayRule == DayRule.AND || domWildcard || dowWildcard) {
            return domOk && dowOk;
        }
        return domOk || dowOk;
    }

    /** True if the wall-clock reading matches (whole seconds only). Says nothing about whether it exists in a zone. */
    public boolean matches(LocalDateTime t) {
        return t.getNano() == 0
                && seconds.get(t.getSecond()) && minutes.get(t.getMinute()) && hours.get(t.getHour())
                && dayMatches(t.toLocalDate());
    }

    // ---------------------------------------------------------------- next fire

    /**
     * Next fire time strictly after {@code after}, in {@code zone}, under the literal wall-clock DST policy
     * described on the class. Sub-second parts of {@code after} are ignored in the sense that the result is always
     * a whole second greater than {@code after}.
     */
    public Optional<ZonedDateTime> nextFire(Instant after, ZoneId zone) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(zone, "zone");
        ZoneRules rules = zone.getRules();
        long start = after.getEpochSecond() + 1; // first whole second strictly after `after`
        while (true) {
            Instant startInstant = Instant.ofEpochSecond(start);
            ZoneOffset offset = rules.getOffset(startInstant);
            ZoneOffsetTransition next = rules.nextTransition(startInstant);
            LocalDateTime from = LocalDateTime.ofEpochSecond(start, 0, offset);
            LocalDateTime cand = nextLocal(from);
            if (cand == null) {
                return Optional.empty();
            }
            long candEpoch = cand.toEpochSecond(offset);
            // The candidate is only valid if it lies in the segment where `offset` applies.
            if (next == null || candEpoch < next.getInstant().getEpochSecond()) {
                return Optional.of(ZonedDateTime.ofInstant(Instant.ofEpochSecond(candEpoch), zone));
            }
            start = next.getInstant().getEpochSecond();
        }
    }

    public Optional<ZonedDateTime> nextFire(ZonedDateTime after) {
        return nextFire(after.toInstant(), after.getZone());
    }

    /** Up to {@code count} consecutive fire times after {@code after}. */
    public List<ZonedDateTime> nextFires(Instant after, ZoneId zone, int count) {
        List<ZonedDateTime> out = new ArrayList<>();
        Instant cursor = after;
        for (int i = 0; i < count; i++) {
            Optional<ZonedDateTime> n = nextFire(cursor, zone);
            if (n.isEmpty()) {
                break;
            }
            out.add(n.get());
            cursor = n.get().toInstant();
        }
        return out;
    }

    /** Smallest matching local date-time that is not before {@code from}, or null if none within the horizon. */
    LocalDateTime nextLocal(LocalDateTime from) {
        LocalDate d = from.toLocalDate();
        int limitYear = d.getYear() + MAX_SEARCH_YEARS;
        int h = from.getHour();
        int m = from.getMinute();
        int s = from.getSecond();
        while (d.getYear() <= limitYear) {
            if (!months.get(d.getMonthValue())) {
                d = d.withDayOfMonth(1).plusMonths(1);
            } else {
                if (dayMatches(d)) {
                    int tod = nextTimeOfDay(h, m, s);
                    if (tod >= 0) {
                        return d.atTime(tod / 3600, tod % 3600 / 60, tod % 60);
                    }
                }
                d = d.plusDays(1);
            }
            h = 0;
            m = 0;
            s = 0;
        }
        return null;
    }

    /** Smallest second-of-day that is at or after h:m:s and matches the time fields, or -1. */
    private int nextTimeOfDay(int h, int m, int s) {
        for (int hh = hours.nextSetBit(h); hh >= 0; hh = hours.nextSetBit(hh + 1)) {
            int mFrom = hh == h ? m : 0;
            for (int mm = minutes.nextSetBit(mFrom); mm >= 0; mm = minutes.nextSetBit(mm + 1)) {
                int sFrom = (hh == h && mm == m) ? s : 0;
                int ss = seconds.nextSetBit(sFrom);
                if (ss >= 0) {
                    return hh * 3600 + mm * 60 + ss;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CronExpression c && c.source.equals(source) && c.dayRule == dayRule;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, dayRule);
    }

    @Override
    public String toString() {
        return source;
    }
}
