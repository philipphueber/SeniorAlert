package app.phueber.senioralert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Stops ringing. Target of the notification's "Turn off" button and of the
 * backup AlarmManager timeout. A broadcast (not a service intent) is used on
 * purpose: it works whatever state the service is in and can never start it.
 *
 * ACTION_REPOST is sent when the user swipes the notification away (possible for
 * foreground-service notifications since Android 14): it is put straight back so
 * the "Turn off" button cannot get lost while ringing.
 */
public final class StopReceiver extends BroadcastReceiver {
    static final String ACTION_STOP = "app.phueber.senioralert.STOP";
    static final String ACTION_REPOST = "app.phueber.senioralert.REPOST";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_REPOST.equals(intent.getAction())) RingService.repostNotification();
        else RingService.stop(ctx);
    }
}
