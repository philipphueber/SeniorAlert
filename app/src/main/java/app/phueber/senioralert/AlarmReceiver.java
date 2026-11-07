package app.phueber.senioralert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fired by AlarmManager at an alarm's time (see Scheduler). One-shot: switches the alarm off, then rings. */
public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Scheduler.ACTION_FIRE.equals(intent.getAction())) return;
        int id = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, -1);
        Store store = new Store(ctx);
        Alarm a = store.find(id);
        // Deleted or switched off after it was armed: a stale fire, stay silent.
        if (a == null || !a.enabled) return;

        // Alarms ring once and must be switched on again by hand. Persist OFF BEFORE
        // ringing, so a crash or kill while ringing can never leave a stale ON alarm
        // that the list shows as armed but AlarmManager no longer holds.
        try {
            store.update(a.withEnabled(false));
        } catch (RuntimeException e) {
            Log.e(TAG, "could not switch alarm off", e); // still ring now
        }
        try {
            RingService.start(ctx, id);
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException. Nothing is playing then,
            // so there is nothing that would need switching off.
            Log.e(TAG, "could not start ringing", e);
        }
    }
}
