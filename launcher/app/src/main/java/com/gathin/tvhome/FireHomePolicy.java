package com.gathin.tvhome;

import java.util.ArrayDeque;

/**
 * Decides what FireHome does with each signal from Fire OS. Plain Java with times passed in (any monotonic
 * millisecond clock), so it is unit tested without a TV.
 *
 * Signals: Amazon's HOME_PRESSED broadcast (a real Home press, about half a second before Amazon's window
 * appears) and accessibility window changes. Rules:
 * - Amazon's HomeActivity coming up means cover it and bring this launcher back, unless:
 *   paused for "Amazon home screen", within SETTINGS_GRACE_MS of a settings window (Amazon's home can flash on
 *   the way into or out of Settings), the launcher is already in front (a late event), or the loop guard tripped.
 * - Amazon's settings screens (they live in Amazon's launcher package, or com.amazon.tv.settings*) are never covered.
 * - Any other app's window drops the cover (the person or the app moved on) and ends an "Amazon home screen" pause.
 * - One Home press can produce the broadcast plus one or two window events: acts within DEDUPE_MS count once.
 * - LOOP_MAX acts within LOOP_WINDOW_MS means something is fighting the redirect (a crashing launcher, say), so it
 *   stops for COOLDOWN_MS and Amazon's home stays usable.
 */
final class FireHomePolicy {

    enum Action { NONE, COVER, DROP, SHOWN }

    static final String AMAZON = "com.amazon.tv.launcher";
    static final long SETTINGS_GRACE_MS = 1000;
    static final long DEDUPE_MS = 1500;
    static final int LOOP_MAX = 6;
    static final long LOOP_WINDOW_MS = 10_000;
    static final long COOLDOWN_MS = 60_000;
    static final long PAUSE_MAX_MS = 30 * 60_000;
    /** Amazon sends HOME_PRESSED when we open its home screen ourselves too: that is not the person pressing Home. */
    static final long PAUSE_IGNORE_HOME_MS = 2000;

    private static final long NEVER = Long.MIN_VALUE / 4;

    private final String ownPackage;
    private long settingsSeenAt = NEVER;
    private long lastActAt = NEVER;
    private long cooldownUntil = NEVER;
    private long pausedUntil = NEVER;
    private long ignoreHomeUntil = NEVER;
    private boolean launcherInFront;
    private final ArrayDeque<Long> acts = new ArrayDeque<>();

    FireHomePolicy(String ownPackage) {
        this.ownPackage = ownPackage;
    }

    /** "Amazon home screen" in Launcher settings: leave Amazon's home alone until Home or another app. */
    void pause(long now) {
        pausedUntil = now + PAUSE_MAX_MS;
        ignoreHomeUntil = now + PAUSE_IGNORE_HOME_MS;
    }

    boolean paused(long now) {
        return now < pausedUntil;
    }

    void launcherShown() {
        launcherInFront = true;
    }

    void launcherHidden() {
        launcherInFront = false;
    }

    /** Amazon's HOME_PRESSED broadcast: the person pressed Home (or Amazon's home was opened by us, just now). */
    Action onHomePressed(long now) {
        if (now < ignoreHomeUntil) return Action.NONE;
        pausedUntil = NEVER;   // Home on Amazon's home screen goes back to this launcher
        return act(now) ? Action.COVER : Action.NONE;
    }

    Action onWindow(String pkg, String cls, long now) {
        if (pkg == null) return Action.NONE;
        if (pkg.equals(ownPackage)) {
            pausedUntil = NEVER;
            return Action.SHOWN;
        }
        if (isTransient(pkg)) return Action.NONE;
        if (isSettings(pkg, cls)) {
            settingsSeenAt = now;
            return Action.DROP;
        }
        if (pkg.equals(AMAZON)) {
            if (cls == null || !cls.contains("HomeActivity")) return Action.NONE;   // a dialog or panel in Amazon's launcher
            if (paused(now) || launcherInFront) return Action.NONE;
            if (now - settingsSeenAt < SETTINGS_GRACE_MS) return Action.NONE;
            return act(now) ? Action.COVER : Action.NONE;
        }
        pausedUntil = NEVER;   // another app opened: Amazon's home is covered again next time
        return Action.DROP;
    }

    /** Records an act unless it repeats the last one or the loop guard stops it. */
    private boolean act(long now) {
        if (now < cooldownUntil) return false;
        if (now - lastActAt < DEDUPE_MS) return true;   // same press: cover again, but don't count it
        while (!acts.isEmpty() && now - acts.peekFirst() >= LOOP_WINDOW_MS) acts.pollFirst();
        if (acts.size() >= LOOP_MAX) {
            cooldownUntil = now + COOLDOWN_MS;
            acts.clear();
            return false;
        }
        acts.addLast(now);
        lastActAt = now;
        return true;
    }

    /** Windows that pop up over whatever is in front without replacing it. */
    static boolean isTransient(String pkg) {
        return pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.amazon.tv.ime")
                || pkg.startsWith("com.amazon.tv.notification") || pkg.startsWith("com.amazon.tv.quicksettings");
    }

    static boolean isSettings(String pkg, String cls) {
        if (pkg.startsWith("com.amazon.tv.settings") || pkg.equals("com.android.settings")) return true;
        return pkg.equals(AMAZON) && cls != null && cls.contains("Settings");
    }
}
