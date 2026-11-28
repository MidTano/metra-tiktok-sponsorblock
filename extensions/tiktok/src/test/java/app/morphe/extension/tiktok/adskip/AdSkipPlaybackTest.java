package app.morphe.extension.tiktok.adskip;

import org.junit.Test;

import static org.junit.Assert.*;

public class AdSkipPlaybackTest {
    @Test public void skipsAfterScrubbingBackIntoSameMarkerEvenCloseToItsEnd() {
        assertEquals(8000, AdSkipPlayback.target(4300, 12000, 4000, 8000));
        assertEquals(-1, AdSkipPlayback.target(8000, 12000, 4000, 8000));
        for (int i = 0; i < 8; i++) {
            assertEquals(8000, AdSkipPlayback.target(7990 - i, 12000, 4000, 8000));
        }
    }

    @Test public void tailSeeksToLastPlayableMillisecond() {
        assertEquals(9999, AdSkipPlayback.target(9950, 10000, 9000, 11000));
        assertEquals(-1, AdSkipPlayback.target(9999, 10000, 9000, 11000));
    }

    @Test public void missingLandingCanBeRetriedAndRewindClearsTheWait() {
        assertTrue(AdSkipPlayback.waitingForSeek(1050, 1000, 4300, 4000, false));
        assertFalse(AdSkipPlayback.waitingForSeek(1550, 1000, 4300, 4000, false));
        assertFalse(AdSkipPlayback.waitingForSeek(1050, 1000, 3500, 4000, false));
    }

    @Test public void onlyThisOccurrenceGetsWatchOverrideAndVotesStayLocal() {
        assertFalse(AdSkipPlayback.canSkip(false, 0));
        assertFalse(AdSkipPlayback.canSkip(true, -1));
        assertTrue(AdSkipPlayback.canSkip(true, 0));
        assertTrue(AdSkipPlayback.canSkip(true, 1));
        assertTrue(AdSkipPlayback.isWatchOverride(3, 4000, 8000, 3, 4100, 8050));
        assertFalse(AdSkipPlayback.isWatchOverride(3, 4000, 8000, 3, 8000, 9000));
        assertFalse(AdSkipPlayback.isWatchOverride(3, 4000, 8000, 4, 4000, 8000));
    }
}
