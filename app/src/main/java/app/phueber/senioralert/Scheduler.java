package app.phueber.senioralert;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.util.TimeZone;

/**
 * Arms and disarms alarms with AlarmManager.
 *
 * Uses setAlarmClock(): it is exact, fires in Doze, shows the alarm icon in the
 * status bar, and an exact alarm puts the app on the temporary allowlist that
 * lets AlarmReceiver start the foreground RingService from the background.
 * Exact alarms are granted by USE_EXACT_ALARM on API 33+ and SCHEDULE_EXACT_ALARM
 * (granted by default) on API 31-32. If exact alarms are nevertheless not allowed,
 * we fall back to setAndAllowWhileIdle so the alarm still rings, if a few minutes
 * late; MainActivity shows the caregiver a warning in that case.
 */
public final class Scheduler {
    public static final String ACTION_FIRE = "app.phueber.senioralert.FIRE";
    public static final String EXTRA_ALARM_ID = "alarm_id";

    private Scheduler() {}

    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am.canScheduleExactAlarms();
    }

    /** Arms the alarm if it is enabled, otherwise cancels it. */
    public static void apply(Context ctx, Alarm a) {
        if (a.enabled) schedule(ctx, a);
        else cancel(ctx, a.id);
    }

    public static void schedule(Context ctx, Alarm a) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long at = NextTrigger.next(a.hour, a.minute, System.currentTimeMillis(), TimeZone.getDefault());
        PendingIntent fire = fireIntent(ctx, a.id);
        if (canScheduleExact(ctx)) {
            PendingIntent show = PendingIntent.getActivity(ctx, 0,
                    new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
                return;
            } catch (SecurityException e) {
                // Permission revoked between the check and the call: fall through.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
    }

    public static void cancel(Context ctx, int id) {
        ctx.getSystemService(AlarmManager.class).cancel(fireIntent(ctx, id));
    }

    /**
     * Re-arms every enabled alarm from scratch. For boot, time or time-zone change,
     * app update and permission change, where the old trigger time is gone or wrong.
     */
    public static void rescheduleAll(Context ctx) {
        for (Alarm a : new Store(ctx).alarms()) apply(ctx, a);
    }

    /**
     * Arms only enabled alarms that AlarmManager no longer holds (e.g. after a
     * force-stop), leaving pending ones untouched. Used when the app opens: re-arming
     * a pending alarm would recompute its time, and an inexact fallback alarm that is
     * a few minutes late would then be pushed to tomorrow and silently skipped today.
     */
    public static void ensureArmed(Context ctx) {
        for (Alarm a : new Store(ctx).alarms()) {
            if (a.enabled && existingFireIntent(ctx, a.id) == null) schedule(ctx, a);
        }
    }

    private static PendingIntent existingFireIntent(Context ctx, int id) {
        return PendingIntent.getBroadcast(ctx, id, fireIntentBase(ctx, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
    }

    private static PendingIntent fireIntent(Context ctx, int id) {
        return PendingIntent.getBroadcast(ctx, id, fireIntentBase(ctx, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Intent fireIntentBase(Context ctx, int id) {
        return new Intent(ctx, AlarmReceiver.class)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("senioralert://alarm/" + id)) // unique per alarm
                .putExtra(EXTRA_ALARM_ID, id);
    }
}
