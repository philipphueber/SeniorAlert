package app.phueber.senioralert;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

/**
 * Plays the alarm sound and vibrates, as a foreground service.
 *
 * SAFETY INVARIANT (from the user): ringing can never be left running without a
 * way to switch it off. Ways to stop, all ending in shutdown()/teardown():
 *   - RingActivity's huge button and the volume keys,
 *   - the notification's "Turn off" action (broadcast to StopReceiver),
 *   - MainActivity's banner (stop()),
 *   - a main-looper timeout exactly RING_MS after ringing STARTED (a hard cap: a
 *     second alarm firing meanwhile does not extend it),
 *   - a backup AlarmManager alarm at RING_MS + 10 s (StopReceiver), in case the
 *     handler message is lost (e.g. the service object was replaced),
 *   - process death: MediaPlayer and Vibrator die with the process (a stuck main
 *     thread ends in an ANR kill, which also stops the sound).
 * The wake lock's own timeout is NOT a stop path; it only bounds the CPU hold.
 * All state lives on the main thread; every entry point that may come from
 * elsewhere hops to it.
 */
public final class RingService extends Service {
    private static final String TAG = "RingService";
    private static final String ACTION_START = "app.phueber.senioralert.RING_START";
    private static final String CHANNEL = "ringing";
    private static final int NOTIF_ID = 1;
    private static final long RING_MS = 2 * 60 * 1000L;
    private static final long BACKUP_MS = RING_MS + 10_000L;
    // Longer than RING_MS so it never cuts ringing short, but bounded so it can never leak.
    private static final long WAKE_MS = 150_000L;
    private static final long TONE_PERIOD_MS = 1500L;

    private static final int RC_ACTIVITY = 1, RC_STOP_ACTION = 2, RC_BACKUP = 3, RC_REPOST = 4;

    /** True only while sound/vibration may actually be active. Set before playing, cleared in teardown. */
    private static volatile boolean ringing;
    /** The live service (main thread only), so stop() can tear down directly. */
    private static RingService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::shutdown;
    private final Runnable toneLoop = new Runnable() {
        @Override public void run() {
            ToneGenerator t = tone;
            if (t == null) return;
            t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000);
            handler.postDelayed(this, TONE_PERIOD_MS);
        }
    };

    private MediaPlayer player;
    private ToneGenerator tone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    // ---- Public API -------------------------------------------------------

    public static void start(Context ctx, int alarmId) {
        Intent i = new Intent(ctx, RingService.class).setAction(ACTION_START)
                .putExtra(Scheduler.EXTRA_ALARM_ID, alarmId);
        ctx.startForegroundService(i);
    }

    /**
     * Stops ringing. Safe from any thread, in any state, any number of times; never
     * throws and never starts the service.
     */
    public static void stop(Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            if (Looper.myLooper() == Looper.getMainLooper()) stopOnMain(app);
            else new Handler(Looper.getMainLooper()).post(() -> stopOnMain(app));
        } catch (Throwable t) {
            Log.e(TAG, "stop failed", t);
        }
    }

    public static boolean isRinging() {
        return ringing;
    }

    /** Puts the notification back if the user swiped it away while ringing. */
    static void repostNotification() {
        RingService s = instance;
        if (s == null || !ringing) return;
        try {
            s.getSystemService(NotificationManager.class).notify(NOTIF_ID, s.buildNotification());
        } catch (RuntimeException e) {
            Log.e(TAG, "repost failed", e);
        }
    }

    private static void stopOnMain(Context app) {
        RingService s = instance;
        if (s != null) s.shutdown(); // direct path: does not depend on intent delivery
        // Without a live service nothing of ours can be playing in this process, so the
        // flag must not claim otherwise (e.g. if a teardown step misbehaved).
        ringing = false;
        // Belt and braces: stopService never creates the service, and it also covers a
        // service that exists but has not reached onStartCommand yet.
        try { app.stopService(new Intent(app, RingService.class)); } catch (Throwable t) { Log.e(TAG, "stopService", t); }
    }

    // ---- Lifecycle --------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent means the system restarted us after a kill: never resume ringing.
        // Anything other than START (unknown action) is treated as stop.
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        try {
            begin();
        } catch (Throwable t) {
            // Whatever failed, do not leave half-started sound behind.
            Log.e(TAG, "start failed", t);
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        // Every startForegroundService() must be answered by startForeground(), so
        // this runs on every START, also when we are already ringing.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            // Without the foreground notification there is no full-screen ring screen
            // and no "Turn off" button, and the system may kill us at any moment. Playing
            // anyway would mean sound the user may have no visible way to stop, so we
            // stop cleanly instead: the user's invariant outranks one missed alarm.
            Log.e(TAG, "startForeground failed, not ringing", e);
            shutdown();
            return;
        }

        if (!ringing) {
            ringing = true; // before any sound starts; teardown clears it
            acquireWakeLock();
            startSound();
            startVibration();
            // The 2-minute limit is a hard cap from the first START. A second alarm
            // firing while ringing joins the current ring; it does not extend it.
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, RING_MS);
            armBackupStop();
        }
        launchScreenIfNotificationCannot();
    }

    @Override
    public void onDestroy() {
        teardown();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Stops everything and ends the service. Idempotent. */
    private void shutdown() {
        teardown();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable t) { Log.e(TAG, "stopForeground", t); }
        try { stopSelf(); } catch (Throwable t) { Log.e(TAG, "stopSelf", t); }
    }

    /**
     * Releases every resource. Each step has its own try/catch so one failure can
     * never skip the rest; safe to call any number of times.
     */
    private void teardown() {
        try { handler.removeCallbacks(timeout); } catch (Throwable t) { Log.e(TAG, "timeout", t); }
        try { handler.removeCallbacks(toneLoop); } catch (Throwable t) { Log.e(TAG, "toneLoop", t); }
        try {
            MediaPlayer p = player;
            player = null;
            if (p != null) {
                try { p.stop(); } finally { p.release(); }
            }
        } catch (Throwable t) { Log.e(TAG, "player", t); }
        try {
            ToneGenerator g = tone;
            tone = null;
            if (g != null) {
                try { g.stopTone(); } finally { g.release(); }
            }
        } catch (Throwable t) { Log.e(TAG, "tone", t); }
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Throwable t) { Log.e(TAG, "vibrator", t); }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable t) { Log.e(TAG, "wakeLock", t); }
        try {
            getSystemService(AlarmManager.class).cancel(pending(RC_BACKUP, StopReceiver.ACTION_STOP));
        } catch (Throwable t) { Log.e(TAG, "backup alarm", t); }
        try {
            getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        } catch (Throwable t) { Log.e(TAG, "notification", t); }
        // Last, so the flag never reads false while a step above was still pending.
        ringing = false;
    }

    // ---- Sound and vibration ---------------------------------------------

    private static final AudioAttributes ALARM_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    /**
     * The phone's own alarm sound. DEFAULT_ALARM_ALERT_URI is the platform's
     * indirection to whatever the user picked, and MediaPlayer resolves it through
     * the copy Android caches in device-protected storage, so it also plays before
     * the first unlock after a reboot. The ringtone is the second choice; the tone
     * is the last resort, e.g. when the user set the alarm sound to "None".
     */
    private void startSound() {
        Uri[] candidates = { Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI };
        for (Uri uri : candidates) {
            MediaPlayer p = new MediaPlayer();
            try {
                p.setAudioAttributes(ALARM_AUDIO);
                p.setDataSource(this, uri);
                p.setLooping(true);
                p.setOnErrorListener((mp, what, extra) -> {
                    // Playback broke mid-ring: switch to the tone so it stays audible.
                    if (mp == player) {
                        player = null;
                        mp.release();
                        startTone();
                    }
                    return true;
                });
                p.prepare(); // local files only; quick enough for the main thread
                p.start();
                player = p;
                return;
            } catch (Exception e) {
                // Common before first unlock: the user's ringtone is in encrypted storage.
                Log.w(TAG, "cannot play " + uri, e);
                p.release();
            }
        }
        startTone();
    }

    /** Last resort when no ringtone can be played. Volume stays the user's alarm volume. */
    private void startTone() {
        if (!ringing || tone != null) return;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            handler.post(toneLoop);
        } catch (RuntimeException e) {
            Log.e(TAG, "no tone either", e);
        }
    }

    private void startVibration() {
        try {
            vibrator = getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 800, 600};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), ALARM_AUDIO);
        } catch (RuntimeException e) {
            Log.e(TAG, "vibrate", e);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeniorAlert:ring");
                wakeLock.setReferenceCounted(false); // one release always frees it
            }
            wakeLock.acquire(WAKE_MS); // re-acquiring restarts the hard timeout
        } catch (RuntimeException e) {
            Log.e(TAG, "wake lock", e);
        }
    }

    /** Independent second timeout through AlarmManager -> StopReceiver. */
    private void armBackupStop() {
        try {
            AlarmManager am = getSystemService(AlarmManager.class);
            long at = SystemClock.elapsedRealtime() + BACKUP_MS;
            PendingIntent pi = pending(RC_BACKUP, StopReceiver.ACTION_STOP);
            if (Scheduler.canScheduleExact(this)) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                    return;
                } catch (SecurityException e) {
                    // Permission revoked meanwhile: fall back to inexact.
                }
            }
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (RuntimeException e) {
            Log.e(TAG, "backup stop alarm", e); // the handler timeout still applies
        }
    }

    // ---- Notification ----------------------------------------------------

    /** Creates the ringing channel; idempotent (the user's later changes to it are kept). */
    static void ensureChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        // The service plays sound and vibration itself (and stops them); the channel
        // must not add a second copy it cannot stop.
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /**
     * Whether the ring screen and the notification's Turn-off button can appear on
     * their own. False if notifications are off, the ringing channel was turned
     * down below "high" (silent/minimised/off) by the user, or (Android 14+) full-
     * screen notifications are denied. MainActivity warns about it, even locked.
     */
    static boolean ringScreenCanAppear(Context ctx) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (!nm.areNotificationsEnabled()) return false;
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
            if (ch != null && ch.getImportance() < NotificationManager.IMPORTANCE_HIGH) return false;
            return Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Notification buildNotification() {
        ensureChannel(this);

        PendingIntent screen = PendingIntent.getActivity(this, RC_ACTIVITY,
                new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action off = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm),
                getString(R.string.turn_off), pending(RC_STOP_ACTION, StopReceiver.ACTION_STOP)).build();

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.notif_ringing))
                .setContentText(getString(R.string.notif_tap))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .setDeleteIntent(pending(RC_REPOST, StopReceiver.ACTION_REPOST))
                .addAction(off);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** Distinct request codes keep the stop/backup/repost PendingIntents separate. */
    private PendingIntent pending(int requestCode, String action) {
        Intent i = new Intent(this, StopReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * If notifications are blocked or full-screen intents are denied, the ring
     * screen would never appear on its own. Try to open it directly; Android may
     * refuse a background start, in which case MainActivity's banner and the
     * timeouts remain.
     */
    private void launchScreenIfNotificationCannot() {
        try {
            if (!ringScreenCanAppear(this)) {
                startActivity(new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot open ring screen", e);
        }
    }
}
