package com.github.sup3rmar10.cronscheduler;

/** Thrown when a cron expression cannot be parsed. The message names the field and the offending text. */
public class CronParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public CronParseException(String message) {
        super(message);
    }
}
