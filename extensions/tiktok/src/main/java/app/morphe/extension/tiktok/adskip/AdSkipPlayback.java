package app.morphe.extension.tiktok.adskip;

/** Timing rules for a single seek attempt; a marker itself never imposes a skip limit. */
final class AdSkipPlayback {
    private static final long LEAD_MS = 150;
    private static final long RETRY_MS = 550;
    private static final long ADVANCE_RETRY_MS = 1100;

    private AdSkipPlayback() {}

    static long target(long position, long duration, long start, long end) {
        long landing = Math.min(end, duration - 1);
        if (end <= start || position < Math.max(0, start - LEAD_MS) || position >= landing) return -1;
        return landing;
    }

    static boolean canSkip(boolean enabled, int vote) {
        return enabled && vote >= 0;
    }

    static boolean isWatchOverride(long watchedId, long watchedStart, long watchedEnd,
            long id, long start, long end) {
        return watchedId == id && watchedStart < end && start < watchedEnd;
    }

    static boolean waitingForSeek(long now, long issuedAt, long position, long start, boolean advanced) {
        // Once the player is clearly before the marker, allow the next entrance to skip at once.
        return position >= Math.max(0, start - LEAD_MS)
                && now - issuedAt < (advanced ? ADVANCE_RETRY_MS : RETRY_MS);
    }
}
