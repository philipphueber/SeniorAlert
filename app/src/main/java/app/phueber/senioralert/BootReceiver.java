package app.phueber.senioralert;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * AlarmManager forgets everything on reboot, and wall-clock alarms must be
 * recomputed after time/zone changes, app updates and exact-alarm permission
 * changes. Every manifest action therefore means the same thing: re-arm all
 * enabled alarms.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        // Exported receiver: only act on the system broadcasts we listen for.
        String a = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !Intent.ACTION_TIME_CHANGED.equals(a)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                && !AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(a)) {
            return;
        }
        try {
            Scheduler.rescheduleAll(ctx);
        } catch (RuntimeException e) {
            Log.e("BootReceiver", "reschedule failed", e);
        }
    }
}
