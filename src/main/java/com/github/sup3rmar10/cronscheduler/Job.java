package com.github.sup3rmar10.cronscheduler;

/** Work to run. Any exception or error thrown is recorded against the job and never affects other jobs. */
@FunctionalInterface
public interface Job {
    void run(FireContext context) throws Exception;

    static Job of(Runnable r) {
        return ctx -> r.run();
    }
}
