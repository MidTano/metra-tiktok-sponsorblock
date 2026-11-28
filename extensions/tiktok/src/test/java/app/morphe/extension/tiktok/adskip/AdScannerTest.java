/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdScannerTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @Test public void oldScanCannotReplaceItsSuccessorOrSurviveSettingsChange() throws Exception {
        AdScanner.clearAll();
        int generation = AdScanner.stamp();
        Object old = job("video", generation);
        Object next = job("video", generation);
        jobs().put("video", next);
        assertFalse(publish(old, completed(generation)));
        assertTrue(publish(next, completed(generation)));
        assertNotNull(AdScanner.result("video"));
        Settings.AD_SKIP_RETIMED.save(!Settings.AD_SKIP_RETIMED.get());
        assertFalse(publish(next, completed(generation)));
        assertNull(AdScanner.result("video"));
    }

    @Test public void disableThenMarkDoesNotReintroduceTheDisabledScan() throws Exception {
        AdScanner.clearAll();
        Landmarks.Print cut = new Landmarks.Print(new int[] {7}, new int[] {0}, 1, 100, 0);
        AdSegment first = AdSegmentStore.add("first", 1000, "video", cut);
        int oldGeneration = AdScanner.stamp();
        Object old = job("video", oldGeneration);
        jobs().put("video", old);
        assertTrue(publish(old, new AdScanner.Result(AdScanner.State.COMPLETE, oldGeneration,
                Collections.singletonList(new AdMatcher.Match(first, 1000, 2000, 30, false)), 5000, 0, null)));
        AdSegmentStore.setEnabled(first.id, false);
        AdSegment second = AdSegmentStore.add("second", 1000, "video", cut);
        AdScanner.recordMarked("video", second, 3000, 4000, 1, 5000, AdScanner.stamp());
        AdScanner.Result marked = AdScanner.result("video");
        assertNotNull(marked);
        assertEquals(AdScanner.State.PARTIAL, marked.state);
        assertEquals(1, marked.matches.size());
        assertSame(second, marked.matches.get(0).segment);
        assertEquals(3000, marked.matches.get(0).startMs);
    }

    @Test public void mergedSnapshotIsReusedUntilManualMarkChanges() throws Exception {
        AdScanner.clearAll();
        Landmarks.Print cut = new Landmarks.Print(new int[] {7}, new int[] {0}, 1, 100, 0);
        AdSegment segment = AdSegmentStore.add("mark", 1000, "video", cut);
        int generation = AdScanner.stamp();
        Object scan = job("video", generation);
        jobs().put("video", scan);
        assertTrue(publish(scan, new AdScanner.Result(AdScanner.State.COMPLETE, generation,
                java.util.Arrays.asList(new AdMatcher.Match(segment, 1000, 2100, 30, false),
                        new AdMatcher.Match(segment, 3500, 4500, 30, false)), 5000, 0, null)));
        AdScanner.recordMarked("video", segment, 1100, 2000, 1, 5000, generation);
        AdScanner.Result first = AdScanner.result("video");
        assertEquals(2, first.matches.size());
        assertEquals(1100, first.matches.get(0).startMs);
        assertSame(first, AdScanner.result("video"));
        AdScanner.recordMarked("video", segment, 1200, 2000, 1, 5000, generation);
        AdScanner.Result second = AdScanner.result("video");
        assertNotSame(first, second);
        assertEquals(1200, second.matches.get(0).startMs);
        assertSame(second, AdScanner.result("video"));
        AdSegmentStore.setEnabled(segment.id, true);
        assertEquals(generation, AdScanner.stamp());
        AdSegmentStore.setEnabled(segment.id, false);
        assertNull(AdScanner.result("video"));
    }

    private static Object job(String aid, int generation) throws Exception {
        Class<?> type = Class.forName(AdScanner.class.getName() + "$Job");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, Object.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(aid, new Object(), generation);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs() throws Exception {
        Field field = AdScanner.class.getDeclaredField("JOBS");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(null);
    }

    private static boolean publish(Object job, AdScanner.Result result) throws Exception {
        Method publish = AdScanner.class.getDeclaredMethod("publish", job.getClass(), AdScanner.Result.class);
        publish.setAccessible(true);
        return (boolean) publish.invoke(null, job, result);
    }

    private static AdScanner.Result completed(int generation) {
        return new AdScanner.Result(AdScanner.State.COMPLETE, generation, Collections.emptyList(), 5000, 0, null);
    }
}
