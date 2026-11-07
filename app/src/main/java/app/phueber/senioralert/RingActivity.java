package app.phueber.senioralert;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.ImageView;
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

        // All red (RingTheme), one huge white button with red text: unmistakably the
        // thing to press, and high contrast either way.
        // The outer frame takes the system-bar insets (fitsSystemWindows replaces its
        // padding), the inner column keeps our own margins.
        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true); // clear of status and gesture bars
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, dp(8));
        frame.addView(root);

        clock = new OneLineText(this, 72);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setGravity(Gravity.CENTER);

        // The button: a white rounded panel, clickable as a whole, with the alarm icon
        // and the label grouped in its centre (a Button would pin the icon to its top).
        int red = getColor(R.color.stop_red);
        FrameLayout off = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(36));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(36));
        off.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33C62828), shape, mask));
        off.setClickable(true);
        off.setFocusable(true);
        off.setContentDescription(getString(R.string.turn_off));
        off.setOnClickListener(v -> turnOff());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.ic_alarm);
        bell.setImageTintList(ColorStateList.valueOf(red));
        content.addView(bell, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView label = new TextView(this);
        label.setText(R.string.turn_off);
        label.setTextColor(red);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(12);
        content.addView(label, llp);
        off.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); // fills the rest of the screen
        lp.bottomMargin = dp(12);
        root.addView(off, lp);

        // The clock goes at the bottom: when the phone is in use, Android shows the
        // alarm notification as a heads-up over the top of the screen, which would
        // hide a clock placed there. Over the button it is harmless (still huge).
        root.addView(clock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(frame);
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
