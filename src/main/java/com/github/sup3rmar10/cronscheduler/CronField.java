package com.github.sup3rmar10.cronscheduler;

/** The six cron fields, in the order they appear in a six-field expression. */
public enum CronField {
    SECOND("second", 0, 59),
    MINUTE("minute", 0, 59),
    HOUR("hour", 0, 23),
    DAY_OF_MONTH("day-of-month", 1, 31),
    MONTH("month", 1, 12),
    /** 0-7 are accepted in text (0 and 7 are both Sunday); stored values are 0-6 with Sunday = 0. */
    DAY_OF_WEEK("day-of-week", 0, 7);

    private final String label;
    private final int min;
    private final int max;

    CronField(String label, int min, int max) {
        this.label = label;
        this.min = min;
        this.max = max;
    }

    public String label() {
        return label;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    int span() {
        return max - min + 1;
    }
}
