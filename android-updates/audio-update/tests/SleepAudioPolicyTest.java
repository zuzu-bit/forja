import com.forja.app.feature.research.SleepAudioPolicy;

/** Standalone JVM boundary tests; no device, account, clock or Android dependency. */
public final class SleepAudioPolicyTest {
    private static final String COMMAND = "11111111-1111-4111-8111-111111111111";
    private static final String SLEEP = "22222222-2222-4222-a222-222222222222";
    private static final String READY = "33333333-3333-4333-b333-333333333333";
    private static final String OTHER_READY = "44444444-4444-4444-8444-444444444444";
    private static final long START = 1_800_000_000_000L;
    private static final long END = START + 8L * 60L * 60L * 1_000L;
    private static int count;

    private static void check(String name, boolean condition) {
        count++;
        if (!condition) throw new AssertionError(name);
    }

    private static boolean start(long now, long from, long before, long to) {
        return SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP, READY, READY,
            now, from, before, to, true, false);
    }

    public static void main(String[] args) {
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 59_999, 43_200_001, Long.MAX_VALUE})
            check("reject duration " + value, !SleepAudioPolicy.isDurationAllowed(value));
        for (long value : new long[]{60_000, 20 * 60_000L, 8 * 3_600_000L, 43_200_000})
            check("accept duration " + value, SleepAudioPolicy.isDurationAllowed(value));

        check("full chunk", SleepAudioPolicy.chunkStopAt(START, END) == START + 120_000);
        check("short final chunk", SleepAudioPolicy.chunkStopAt(START, START + 18_000) == START + 18_000);
        check("one-second final chunk", SleepAudioPolicy.chunkStopAt(START, START + 1_000) == START + 1_000);
        check("subsecond remainder", SleepAudioPolicy.chunkStopAt(START, START + 999) == 0);
        check("already stopped", SleepAudioPolicy.chunkStopAt(START, START) == 0);
        check("stop in past", SleepAudioPolicy.chunkStopAt(START, START - 1) == 0);
        check("negative epoch", SleepAudioPolicy.chunkStopAt(-1, 120_000) == 0);
        check("negative bound", SleepAudioPolicy.chunkStopAt(Long.MIN_VALUE, Long.MAX_VALUE) == 0);
        check("zero epoch is valid", SleepAudioPolicy.chunkStopAt(0, 120_000) == 120_000);
        check("long maximum final chunk", SleepAudioPolicy.chunkStopAt(Long.MAX_VALUE - 1_000, Long.MAX_VALUE) == Long.MAX_VALUE);
        check("long maximum full chunk", SleepAudioPolicy.chunkStopAt(Long.MAX_VALUE - 200_000, Long.MAX_VALUE) == Long.MAX_VALUE - 80_000);
        check("long maximum no remainder", SleepAudioPolicy.chunkStopAt(Long.MAX_VALUE, Long.MAX_VALUE) == 0);

        check("start boundary inclusive", start(START, START, START + 30_000, END));
        check("last start boundary inclusive", start(START + 30_000, START, START + 30_000, END));
        check("too early", !start(START - 1, START, START + 30_000, END));
        check("too late", !start(START + 30_001, START, START + 30_000, END));
        check("reversed start window", !start(START, START, START - 1, END));
        check("minimum plan", start(START, START, START + 30_000, START + 60_000));
        check("short plan", !start(START, START, START + 30_000, START + 59_999));
        check("maximum plan", start(START, START, START + 30_000, START + 43_200_000));
        check("plan beyond twelve hours", !start(START, START, START + 30_000, START + 43_200_001));
        check("no time left", !start(START + 60_000, START, START + 60_000, START + 60_000));
        check("five seconds too short to start", !start(START + 55_000, START, START + 60_000, START + 60_000));
        check("more than five seconds remain", start(START + 54_999, START, START + 60_000, START + 60_000));
        check("past stop", !start(START, START, START + 30_000, START - 1));
        check("negative now", !start(-1, 0, 30_000, 60_000));
        check("negative plan", !start(0, -1, 30_000, 60_000));
        check("no subtraction overflow", !start(0, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        check("huge plan rejected", !start(0, 0, 30_000, Long.MAX_VALUE));
        check("bounded plan near long maximum", start(Long.MAX_VALUE - 60_000,
            Long.MAX_VALUE - 60_000, Long.MAX_VALUE - 30_000, Long.MAX_VALUE));

        // 23:55 UTC to 00:15 UTC: epoch arithmetic preserves the full twenty-minute plan.
        long midnight = 21_000L * 86_400_000L;
        check("cross-midnight plan", start(midnight - 300_000, midnight - 300_000,
            midnight - 270_000, midnight + 900_000));
        check("chunk crosses midnight", SleepAudioPolicy.chunkStopAt(midnight - 30_000,
            midnight + 900_000) == midnight + 90_000);

        check("explicit sleep consent required", !SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP,
            READY, READY, START, START, START + 30_000, END, false, false));
        check("handled command cannot replay", !SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP,
            READY, READY, START, START, START + 30_000, END, true, true));
        check("old arm cannot start", !SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP,
            READY, OTHER_READY, START, START, START + 30_000, END, true, false));
        check("unarmed cannot start", !SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP,
            READY, null, START, START, START + 30_000, END, true, false));
        for (String invalid : new String[]{null, "", "not-an-id", "11111111-1111-1111-8111-111111111111",
            "11111111-1111-4111-7111-111111111111", COMMAND + "\n", " " + COMMAND}) {
            check("invalid command identifier", !SleepAudioPolicy.remoteStartAllowed(invalid, SLEEP,
                READY, READY, START, START, START + 30_000, END, true, false));
            check("invalid sleep identifier", !SleepAudioPolicy.remoteStartAllowed(COMMAND, invalid,
                READY, READY, START, START, START + 30_000, END, true, false));
            check("invalid armed-session identifier", !SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP,
                invalid, invalid, START, START, START + 30_000, END, true, false));
        }
        check("uppercase uuid accepted", SleepAudioPolicy.remoteStartAllowed(COMMAND, SLEEP.toUpperCase(java.util.Locale.ROOT),
            READY.toUpperCase(java.util.Locale.ROOT), READY.toUpperCase(java.util.Locale.ROOT),
            START, START, START + 30_000, END, true, false));
        System.out.println(count + " sleep-audio timing, consent and replay checks passed");
    }
}
