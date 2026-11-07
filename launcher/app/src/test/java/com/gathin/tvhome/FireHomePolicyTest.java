package com.gathin.tvhome;

import org.junit.Test;

import static com.gathin.tvhome.FireHomePolicy.Action.COVER;
import static com.gathin.tvhome.FireHomePolicy.Action.DROP;
import static com.gathin.tvhome.FireHomePolicy.Action.NONE;
import static com.gathin.tvhome.FireHomePolicy.Action.SHOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FireHomePolicyTest {
    private static final String OWN = "com.gathin.tvhome";
    private static final String AMAZON = "com.amazon.tv.launcher";
    private static final String HOME = "com.amazon.tv.launcher.ui.HomeActivity";

    private final FireHomePolicy policy = new FireHomePolicy(OWN);

    @Test public void amazonHomeIsCovered() {
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 10_000));
    }

    @Test public void homeVnextIsCoveredToo() {
        assertEquals(COVER, policy.onWindow(AMAZON, "com.amazon.tv.launcher.ui.HomeActivity_vNext", 10_000));
    }

    @Test public void amazonSettingsAreNeverCovered() {
        assertEquals(DROP, policy.onWindow(AMAZON, "com.amazon.tv.launcher.ui.MainSettingsActivity", 10_000));
        assertEquals(DROP, policy.onWindow("com.amazon.tv.settings.v2", "x.Settings", 10_000));
    }

    @Test public void homeFlashRightAfterSettingsIsLeftAlone() {
        policy.onWindow(AMAZON, "com.amazon.tv.launcher.ui.MainSettingsActivity", 10_000);
        assertEquals(NONE, policy.onWindow(AMAZON, HOME, 10_300));
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 10_000 + FireHomePolicy.SETTINGS_GRACE_MS));
    }

    @Test public void homePressedCoversEvenRightAfterSettings() {
        policy.onWindow(AMAZON, "com.amazon.tv.launcher.ui.MainSettingsActivity", 10_000);
        assertEquals(COVER, policy.onHomePressed(10_100));
    }

    @Test public void otherAppsDropTheCover() {
        assertEquals(DROP, policy.onWindow("com.netflix.ninja", "com.netflix.ninja.MainActivity", 10_000));
    }

    @Test public void popupsChangeNothing() {
        assertEquals(NONE, policy.onWindow("com.android.systemui", "x", 10_000));
        assertEquals(NONE, policy.onWindow("com.amazon.tv.ime", "x", 10_000));
        assertEquals(NONE, policy.onWindow("com.amazon.tv.quicksettings.ui", "x", 10_000));
        assertEquals(NONE, policy.onWindow(AMAZON, "android.app.Dialog", 10_000));
    }

    @Test public void ownWindowsAreShown() {
        assertEquals(SHOWN, policy.onWindow(OWN, "com.gathin.tvhome.MainActivity", 10_000));
    }

    @Test public void lateEventWhileLauncherIsInFrontIsIgnored() {
        policy.launcherShown();
        assertEquals(NONE, policy.onWindow(AMAZON, HOME, 10_000));
        policy.launcherHidden();
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 10_100));
    }

    @Test public void amazonHomeScreenPauseLastsUntilHome() {
        policy.pause(10_000);
        assertEquals(NONE, policy.onWindow(AMAZON, HOME, 10_200));
        // Amazon's own HOME_PRESSED for the home screen we just opened is not the person pressing Home
        assertEquals(NONE, policy.onHomePressed(10_300));
        assertEquals(NONE, policy.onWindow(AMAZON, HOME, 60_000));
        assertEquals(COVER, policy.onHomePressed(70_000));
        assertFalse(policy.paused(70_001));
    }

    @Test public void amazonHomeScreenPauseEndsWithAnotherApp() {
        policy.pause(10_000);
        assertTrue(policy.paused(20_000));
        policy.onWindow("com.netflix.ninja", "com.netflix.ninja.MainActivity", 20_000);
        assertFalse(policy.paused(20_001));
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 30_000));
    }

    @Test public void amazonHomeScreenPauseSurvivesSettings() {
        policy.pause(10_000);
        policy.onWindow("com.amazon.tv.settings.v2", "x.Settings", 20_000);
        assertTrue(policy.paused(20_001));
    }

    @Test public void onePressCountsOnceAndKeepsCovering() {
        assertEquals(COVER, policy.onHomePressed(10_000));
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 10_400));
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, 10_600));
    }

    @Test public void loopGuardStopsAFight() {
        long t = 10_000;
        for (int i = 0; i < FireHomePolicy.LOOP_MAX; i++, t += FireHomePolicy.DEDUPE_MS) {
            assertEquals(COVER, policy.onWindow(AMAZON, HOME, t));
        }
        assertEquals(NONE, policy.onWindow(AMAZON, HOME, t));
        assertEquals(NONE, policy.onHomePressed(t + 1000));
        assertEquals(COVER, policy.onWindow(AMAZON, HOME, t + FireHomePolicy.COOLDOWN_MS));
    }

    @Test public void normalHomePressesDontTripTheLoopGuard() {
        for (long t = 10_000; t < 120_000; t += 3000) {
            assertEquals(COVER, policy.onHomePressed(t));
        }
    }
}
