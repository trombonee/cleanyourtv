package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fire OS Home takeover for HomeKeyService, on when stock_home_redirect is com.amazon.tv.launcher.
 *
 * Fire OS won't let another app be Home, never passes the Home key to accessibility services, and Android 9
 * (Fire OS 7) holds back activity starts from background apps for 5 s after Home. So:
 * 1. Amazon's unguarded HOME_PRESSED broadcast (sent from its own goHome, ~0.5 s before its window draws) or,
 *    as a backup, the window event for Amazon's HomeActivity, shows a cover: an accessibility overlay holding a
 *    snapshot of this launcher's last frame. Overlays aren't held back, and the snapshot makes the handoff invisible.
 * 2. Behind it, this launcher's existing task is moved to the front (AppTask.moveToFront is not held back on
 *    Fire OS 7). With no task (first time after boot, or Android reclaimed it) an exact alarm starts it, which
 *    is not held either where alarm_manager_constants min_futurity=0, plus a direct start as the last resort.
 * 3. MainActivity gaining focus drops the cover. It fails open after FAIL_OPEN_MS whatever happens, and while it
 *    is up D-pad and OK are swallowed so blind presses don't open things on Amazon's home behind it.
 *
 * FireHomePolicy decides when to act. "Amazon home screen" in Launcher settings pauses it until Home or another app.
 * Ideas from FireFrame (HOME_PRESSED, overlay), the hrudek/bearylabs firetv fork (moveToFront, alarm, fail-open)
 * and andrewsaad/fire-tv-cleanup (settings grace, loop guard), all MIT.
 */
final class FireHome {
    private static final String TAG = "FireHome";
    static final String AMAZON = FireHomePolicy.AMAZON;
    private static final String HOME_PRESSED = "com.amazon.tv.launcher.HOME_PRESSED";
    /** Past Android 9's 5 s hold, so even the direct-start fallback happens under the cover. */
    private static final long FAIL_OPEN_MS = 6000;

    private static FireHome active;
    private static FireHomePolicy policy;
    /** The launcher's last frame at full size in 32-bit colour (8 MB at 1080p), reused: 16-bit bands a smooth
     *  wallpaper. Home keeps the launcher's place rather than resetting it, so the real screen that replaces the
     *  cover is this same frame. */
    private static Bitmap snapshot;
    private static boolean snapshotReady;
    private static final Bitmap.Config snapshotConfig = Bitmap.Config.ARGB_8888;
    /** D-pad and OK presses made while the cover is up, replayed into MainActivity when it takes over, so the
     *  cover answers the remote like the launcher it shows instead of eating the presses. */
    private static final int MAX_QUEUED_KEYS = 8;
    private static final int[] queuedKeys = new int[MAX_QUEUED_KEYS];
    private static int queuedCount;
    private static long shownAt;
    /** MainActivity exists in this process, so its task can be moved. Tracked here: asking the system can block. */
    private static volatile boolean launcherAlive;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private final AlarmManager alarms;
    private final PendingIntent alarmTarget;
    private final ImageView cover;
    private boolean coverAttached;
    private boolean covering;
    private final Runnable failOpen = () -> {
        queuedCount = 0;   // the launcher never came: the presses are not replayed somewhere else later
        hideCover();
    };
    /** HOME_PRESSED can land before Amazon's home has resumed, which then comes up over a task we just moved:
     *  move it again every RETRY_MS until MainActivity has focus (launcherShown) or the cover fails open. */
    private static final long RETRY_MS = 300;
    private final Runnable retryMove = new Runnable() {
        @Override
        public void run() {
            if (!covering) return;
            moveInBackground(false);
            handler.postDelayed(this, RETRY_MS);
        }
    };
    /** AppTask.moveToFront can block for seconds inside system_server while Fire OS holds app switches after Home.
     *  It must never run on the main thread, where it would freeze the cover, the key filter and the window events
     *  that tell us Amazon's home is up (the move after that event is the one that sticks). Retries skip while one
     *  is in flight; a move for a new trigger always gets its own thread. */
    private final AtomicInteger movesInFlight = new AtomicInteger();

    private void moveInBackground(boolean fresh) {
        if (!fresh && movesInFlight.get() > 0) return;
        if (movesInFlight.get() >= 3) return;
        movesInFlight.incrementAndGet();
        new Thread(() -> {
            try {
                if (moveExistingTask()) Log.i(TAG, fresh ? "moved existing task" : "moved existing task again");
            } finally {
                movesInFlight.decrementAndGet();
            }
        }, "FireHomeMove").start();
    }

    private final BroadcastReceiver homePressed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (policy.onHomePressed(SystemClock.uptimeMillis()) == FireHomePolicy.Action.COVER) {
                Log.i(TAG, "HOME_PRESSED");
                coverAndReturn();
            }
        }
    };

    FireHome(AccessibilityService service) {
        this.service = service;
        if (policy == null) policy = new FireHomePolicy(service.getPackageName());
        windows = service.getSystemService(WindowManager.class);
        alarms = service.getSystemService(AlarmManager.class);
        alarmTarget = PendingIntent.getActivity(service, 7421, launcherIntent(service),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(service, 0, 0).toBundle());
        cover = new ImageView(service);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        if (snapshotReady) cover.setImageBitmap(snapshot);
        // Attached once and kept hidden, so showing it is a visibility change, not a window add.
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            windows.addView(cover, lp);
            coverAttached = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cover unavailable", e);
        }
        // Exported: Amazon's launcher sends it. It carries no data and only ever leads to opening this launcher.
        service.registerReceiver(homePressed, new IntentFilter(HOME_PRESSED), Context.RECEIVER_EXPORTED);
        active = this;
    }

    void destroy() {
        hideCover();
        try {
            service.unregisterReceiver(homePressed);
        } catch (RuntimeException ignored) {
        }
        if (coverAttached) {
            try {
                windows.removeViewImmediate(cover);
            } catch (RuntimeException ignored) {
            }
            coverAttached = false;
        }
        if (active == this) active = null;
    }

    void onWindow(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        // A late event from before the launcher came back must not cover it again.
        if (event.getEventTime() <= shownAt && pkg != null && AMAZON.contentEquals(pkg)) return;
        switch (policy.onWindow(pkg == null ? null : pkg.toString(), cls == null ? null : cls.toString(),
                SystemClock.uptimeMillis())) {
            case COVER:
                Log.i(TAG, "window " + cls);
                coverAndReturn();
                break;
            case DROP:
                cancel();
                break;
            default:
                break;
        }
    }

    /** True for keys that must not reach Amazon's home behind the cover; their presses are queued for the launcher. */
    boolean swallow(KeyEvent event) {
        if (!covering) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getAction() == KeyEvent.ACTION_DOWN && queuedCount < MAX_QUEUED_KEYS) {
                    queuedKeys[queuedCount++] = event.getKeyCode();
                    Log.i(TAG, "queued key " + event.getKeyCode());
                }
                return true;
            default:
                return false;
        }
    }

    private void coverAndReturn() {
        Log.i(TAG, "covering Amazon home");
        showCover();
        if (launcherAlive) {
            moveInBackground(true);
            handler.removeCallbacks(retryMove);
            handler.postDelayed(retryMove, RETRY_MS);
            return;
        }
        Log.i(TAG, "no live task: alarm + start");
        try {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), alarmTarget);
        } catch (SecurityException noExactAlarms) {
            // Android 12+ without the exact-alarm permission; the direct start below still runs
        }
        try {
            service.startActivity(launcherIntent(service));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "launcher start failed", e);
        }
    }

    private static boolean isLauncherTask(ActivityManager.RecentTaskInfo info) {
        return info.baseActivity != null && info.topActivity != null
                && MainActivity.class.getName().equals(info.baseActivity.getClassName())
                && MainActivity.class.getName().equals(info.topActivity.getClassName());
    }

    /** Brings MainActivity's live task forward, the fastest path; false when there is none to move. Background only. */
    private boolean moveExistingTask() {
        ActivityManager manager = service.getSystemService(ActivityManager.class);
        try {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (isLauncherTask(task.getTaskInfo())) {
                    task.moveToFront();
                    return true;
                }
            }
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "task move unavailable", unavailable);
        }
        return false;
    }

    private void showCover() {
        if (!covering) queuedCount = 0;   // a new hand-off: nothing from an earlier one carries over
        handler.removeCallbacks(failOpen);
        handler.postDelayed(failOpen, FAIL_OPEN_MS);
        covering = true;
        if (coverAttached) cover.setVisibility(View.VISIBLE);
    }

    private void hideCover() {
        handler.removeCallbacks(failOpen);
        handler.removeCallbacks(retryMove);
        covering = false;
        if (coverAttached) cover.setVisibility(View.GONE);
    }

    /** Another app or a settings screen came up: don't pull the person back. */
    private void cancel() {
        if (!covering) return;
        queuedCount = 0;
        alarms.cancel(alarmTarget);
        hideCover();
    }

    private static Intent launcherIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    // ---- called from the launcher's activities (same process) ---------------------------------------

    static boolean isActive() {
        return active != null;
    }

    static void launcherCreated() {
        launcherAlive = true;
    }

    static void launcherDestroyed() {
        launcherAlive = false;
    }

    /** Presses made under the cover, oldest first, to replay into MainActivity; empty when there were none. */
    static int[] takeQueuedKeys() {
        int[] keys = java.util.Arrays.copyOf(queuedKeys, queuedCount);
        queuedCount = 0;
        return keys;
    }

    /** MainActivity has focus and has drawn: drop the cover. */
    static void launcherShown() {
        shownAt = SystemClock.uptimeMillis();
        if (active != null && active.covering) Log.i(TAG, "launcher shown, cover dropped");
        if (policy != null) policy.launcherShown();
        FireHome home = active;
        if (home != null) {
            home.alarms.cancel(home.alarmTarget);
            home.hideCover();
        }
    }

    /** MainActivity is leaving the screen: keep its last frame for the cover. */
    static void launcherHidden(Window window) {
        if (policy != null) policy.launcherHidden();
        FireHome home = active;
        if (home == null) return;
        View decor = window.getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        if (snapshot == null || snapshot.getWidth() != w || snapshot.getHeight() != h
                || snapshot.getConfig() != snapshotConfig) {
            snapshot = Bitmap.createBitmap(w, h, snapshotConfig);
            snapshotReady = false;
        }
        try {
            PixelCopy.request(window, snapshot, result -> {
                if (result != PixelCopy.SUCCESS) return;
                snapshotReady = true;
                FireHome now = active;
                if (now == null) return;
                // A fresh drawable, so the new pixels are uploaded rather than the old texture reused.
                now.cover.setImageDrawable(null);
                now.cover.setImageBitmap(snapshot);
            }, home.handler);
        } catch (RuntimeException e) {
            Log.w(TAG, "snapshot failed", e);
        }
    }

    static boolean amazonHomeInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(AMAZON, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** "Amazon home screen" in Launcher settings: open it and leave it alone until Home or another app. */
    static void openAmazonHome(Context context) {
        if (policy != null) policy.pause(SystemClock.uptimeMillis());
        FireHome home = active;
        if (home != null) home.hideCover();
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(AMAZON)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
