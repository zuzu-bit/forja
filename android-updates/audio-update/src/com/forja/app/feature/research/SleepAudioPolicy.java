package com.forja.app.feature.research;

import java.util.regex.Pattern;

/** Pure timing and command rules; the service separately checks owner, grant and microphone access. */
public final class SleepAudioPolicy {
    public static final long MIN_DURATION_MS = 60_000L;
    public static final long MAX_DURATION_MS = 12L * 60L * 60L * 1_000L;
    public static final long CHUNK_DURATION_MS = 120_000L;
    private static final Pattern UUID_V4 = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private SleepAudioPolicy() {}

    public static boolean isDurationAllowed(long durationMs) {
        return durationMs >= MIN_DURATION_MS && durationMs <= MAX_DURATION_MS;
    }

    /** Returns the next chunk's epoch deadline, or zero when less than one second remains. */
    public static long chunkStopAt(long now, long plannedStop) {
        if (now < 0 || plannedStop <= now) return 0;
        // Ordered nonnegative values make subtraction safe, including near Long.MAX_VALUE.
        long remaining = plannedStop - now;
        if (remaining < 1_000L) return 0;
        // Addition is safe because the result cannot exceed plannedStop.
        return now + Math.min(CHUNK_DURATION_MS, remaining);
    }

    /**
     * Accepts only an unhandled command for the currently armed microphone session.
     * The caller must persist handled IDs before capture and scope them to the owner/session.
     * All times are epoch milliseconds, so crossing midnight needs no special case.
     */
    public static boolean remoteStartAllowed(
        String commandId, String sleepId, String expectedReadySession, String actualReadySession,
        long now, long startAt, long startBefore, long plannedStop,
        boolean sleepAuthorized, boolean alreadyHandled
    ) {
        if (!sleepAuthorized || alreadyHandled || !uuid(commandId) || !uuid(sleepId)
            || !uuid(expectedReadySession) || !expectedReadySession.equals(actualReadySession)) return false;
        if (now < 0 || startAt < 0 || startBefore < startAt || now < startAt || now > startBefore
            || plannedStop <= now || plannedStop <= startAt) return false;
        return isDurationAllowed(plannedStop - startAt) && plannedStop - now > 5_000L;
    }

    private static boolean uuid(String value) {
        return value != null && UUID_V4.matcher(value).matches();
    }
}
