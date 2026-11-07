package app.phueber.senioralert;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * Full-screen ringing screen: the current time and one huge "Turn off" button.
 * No snooze, nothing else to press by mistake. Works over the lock screen without
 * unlocking. It only shows while RingService rings and closes itself as soon as
 * ringing stops by any other path (timeout, notification, MainActivity).
 */
public final class RingActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!RingService.isRinging()) {
                finish();
                return;
            }
            clock.setText(DateFormat.getTimeFormat(RingActivity.this).format(new Date()));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Opened from a stale notification or after ringing already ended: nothing to show.
        if (!RingService.isRinging()) {
            finish();
            return;
        }
        // The manifest sets showWhenLocked/turnScreenOn too; repeat them in code, plus
        // the legacy flags for API 26 where the manifest attributes do not exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setGravity(Gravity.CENTER);
        root.addView(clock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button off = new Button(this);
        off.setText(R.string.turn_off);
        off.setAllCaps(false);
        off.setTextColor(Color.WHITE);
        off.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        off.setTypeface(Typeface.DEFAULT_BOLD);
        off.setBackgroundColor(getColor(R.color.stop_red));
        off.setOnClickListener(v -> turnOff());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); // fills the rest of the screen
        lp.topMargin = pad;
        root.addView(off, lp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        if (clock != null) tick.run(); // also finishes if ringing ended meanwhile
        else finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** Any volume key turns the alarm off: easy to find by touch, and we consume it. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            turnOff();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Back is ignored rather than dismissing silently: leaving the screen with the
     * alarm still sounding would hide the button. Ignoring it is safe because the
     * button, volume keys, notification and timeouts all remain.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // intentionally empty
    }

    private void turnOff() {
        RingService.stop(this);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
