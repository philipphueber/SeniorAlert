package app.phueber.senioralert;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * The whole app: one screen, built in code and rebuilt from scratch on every change.
 * Deliberately plain buttons only — no Switch, no swipes, no long-press — so nothing
 * can be triggered by accident and there is nothing hidden to discover.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 1;
    private static final String PREF_NOTIF_ASKED = "notif_asked";
    private static final long UNLOCK_IDLE_MS = 10_000L; // PIN prompt closes after 10 s without typing

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private LinearLayout root;
    private boolean shownRinging;
    private String shownAlarms; // encoded list last rendered, to notice outside changes
    private Dialog dialog; // the one open dialog, so a starting alarm can close it

    // Polls so the stop banner appears/disappears on its own, and so a row flips to
    // OFF by itself when AlarmReceiver switches off an alarm that just rang.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean ringing = RingService.isRinging();
            // A dialog would cover the stop banner; ringing must always be stoppable.
            // Checked on every tick, so a dialog opened during ringing closes too.
            if (ringing) dismissDialog();
            // When ringing starts while this screen is open, show the ring screen
            // (we are in the foreground, so Android allows it). The system's heads-up
            // notification would otherwise cover the banner at the top, and a tap
            // meant for the banner would land on the notification instead.
            if (ringing && !shownRinging) openRingScreen();
            if (ringing != shownRinging
                    || !AlarmCodec.encode(store.alarms()).equals(shownAlarms)) rebuild();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // Keeps content clear of the status bar and the gesture bar; Android 15 draws
        // every app edge to edge, so without this Back sits under the gesture bar.
        page.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(12), p, dp(28));
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Back is pinned below the list, always in view: many seniors do not know
        // the system back gesture. It leaves the app for the home screen.
        Button back = outlined(getString(R.string.back), R.color.text_black, 22, 64);
        back.setOnClickListener(v -> goHome());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(p, dp(8), p, dp(12));
        page.addView(back, blp);
        setContentView(page);
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Self-healing: alarms are lost e.g. after a force-stop. Only arms what is
        // missing, so a pending (possibly late, inexact) alarm keeps its time.
        Scheduler.ensureArmed(this);
        maybeAutoRequestNotifications();
        rebuild();
        // Opened (or returned to) while ringing: the ring screen goes on top.
        if (RingService.isRinging()) openRingScreen();
        handler.postDelayed(poll, 1000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        rebuild();
    }

    // ---------------------------------------------------------------- screen

    private void rebuild() {
        root.removeAllViews();
        boolean locked = store.isLocked();

        shownRinging = RingService.isRinging();
        if (shownRinging) {
            Button stop = filled(getString(R.string.ringing_banner) + "\n"
                    + getString(R.string.turn_off), R.color.stop_red, 34, 140);
            stop.setOnClickListener(v -> { RingService.stop(this); rebuild(); });
            root.addView(stop, spaced());
        }

        addWarnings(locked);

        TextView title = text(getString(R.string.title_alarms), 36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = spaced();
        tlp.topMargin = dp(20);
        tlp.bottomMargin = dp(4);
        root.addView(title, tlp);

        List<Alarm> alarms = store.alarms();
        shownAlarms = AlarmCodec.encode(alarms);
        if (alarms.isEmpty()) {
            TextView empty = text(getString(R.string.no_alarms), 24);
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            root.addView(empty, spaced());
        }
        for (Alarm a : alarms) addRow(a, locked);

        if (locked) {
            // Small and plain on purpose: it is for the caregiver, not the senior.
            Button unlock = new Button(this);
            unlock.setText(R.string.unlock);
            unlock.setAllCaps(false);
            unlock.setBackground(ripple(R.color.press_dark, Color.TRANSPARENT, 0, 0));
            unlock.setStateListAnimator(null);
            unlock.setTextColor(getColor(R.color.text_muted));
            unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            int ph = dp(24);
            unlock.setPadding(ph, 0, ph, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(40);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            // Long press only: a senior tapping around cannot open the PIN prompt by
            // accident; the helper, told by the label, holds it.
            unlock.setOnLongClickListener(v -> { showUnlock(); return true; });
            root.addView(unlock, lp);
        } else {
            Button add = filled(getString(R.string.add_alarm), R.color.on_green, 26, 80);
            add.setOnClickListener(v -> pickTime(12, 0, (h, m) -> Scheduler.schedule(this, store.add(h, m))));
            LinearLayout.LayoutParams alp = spaced();
            alp.topMargin = dp(24);
            root.addView(add, alp);
            Button lock = outlined(getString(R.string.lock), R.color.text_black, 22, 64);
            lock.setOnClickListener(v -> showLock());
            root.addView(lock, spaced());
        }
    }

    private void addRow(Alarm a, boolean locked) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        card.setPadding(p, dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        card.setElevation(dp(2)); // a soft shadow lifts the card off the page

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = new OneLineText(this, 58);
        time.setText(format(a));
        // OFF alarms fade back, so the ones that will ring stand out at a glance.
        time.setTextColor(getColor(a.enabled ? R.color.text_black : R.color.text_muted));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        timeLp.setMarginEnd(dp(12)); // a shrunk time must not touch ON/OFF
        top.addView(time, timeLp);
        Button toggle = a.enabled
                ? filled(getString(R.string.state_on), R.color.on_green, 26, 72)
                : tinted(getString(R.string.state_off), R.color.off_bg, R.color.off_text, 26, 72);
        toggle.setMinWidth(dp(128));
        toggle.setMinimumWidth(dp(128));
        toggle.setOnClickListener(v -> {
            Alarm changed = a.withEnabled(!a.enabled);
            store.update(changed);
            Scheduler.apply(this, changed);
            rebuild();
        });
        top.addView(toggle);
        card.addView(top);

        if (!locked) {
            // Secondary actions: outlined and side by side, so they don't compete with
            // ON/OFF or look like the red Turn off.
            LinearLayout actions = new LinearLayout(this);
            Button change = outlined(getString(R.string.change_time), R.color.text_black, 19, 56);
            change.setOnClickListener(v -> pickTime(a.hour, a.minute, (h, m) -> {
                Alarm changed = a.withTime(h, m).withEnabled(true); // picking a time means "ring then"
                store.update(changed);
                Scheduler.apply(this, changed);
            }));
            Button delete = outlined(getString(R.string.delete), R.color.stop_red, 19, 56);
            delete.setOnClickListener(v -> confirmDelete(a));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            left.rightMargin = dp(6);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            right.leftMargin = dp(6);
            actions.addView(change, left);
            actions.addView(delete, right);
            LinearLayout.LayoutParams alp = spaced();
            alp.topMargin = dp(12);
            card.addView(actions, alp);
        }
        root.addView(card, spaced());
    }

    // ---------------------------------------------------------------- warnings

    /**
     * Setup problems, for the helper. Locked, only the ones that would stop the ring
     * screen and its Turn-off button from appearing are shown: those affect whether a
     * ringing alarm can be switched off, so they must not hide behind the PIN.
     */
    private void addWarnings(boolean locked) {
        String pkg = getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        NotificationManager nm = getSystemService(NotificationManager.class);

        boolean fullScreenDenied = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent();
        if (!notificationsAllowed() || (!fullScreenDenied && !RingService.ringScreenCanAppear(this))) {
            // Also covers the ringing channel turned down to silent/off in system settings.
            warning(R.string.warn_notifications, v -> fixNotifications());
        }
        if (fullScreenDenied) {
            warning(R.string.warn_full_screen, v -> open(new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri), null));
        }
        if (locked) return;

        if (!Scheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            warning(R.string.warn_exact, v -> open(new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri), null));
        }
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg)) {
            warning(R.string.warn_battery, v -> open(
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        }
    }

    private void warning(int textRes, View.OnClickListener onClick) {
        TextView w = text("⚠  " + getString(textRes), 19);
        int p = dp(18);
        w.setPadding(p, p, p, p);
        w.setMinHeight(dp(64));
        w.setBackground(ripple(R.color.press_dark, getColor(R.color.warn_yellow),
                getColor(R.color.warn_border), 20));
        w.setOnClickListener(onClick);
        root.addView(w, spaced());
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private void fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Never asked, or the system still allows asking: show the system prompt.
            // Otherwise it was denied for good and only the settings page can fix it.
            if (!notifPrefs().getBoolean(PREF_NOTIF_ASKED, false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestNotifications();
                return;
            }
        }
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), null);
    }

    /** Ask once on the first unlocked start; the warning box covers later cases. */
    private void maybeAutoRequestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || store.isLocked()
                || notifPrefs().getBoolean(PREF_NOTIF_ASKED, false)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestNotifications();
    }

    private void requestNotifications() {
        notifPrefs().edit().putBoolean(PREF_NOTIF_ASKED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private SharedPreferences notifPrefs() {
        return getPreferences(MODE_PRIVATE);
    }

    /** Opens a settings screen; some vendors lack some screens, hence the fallback. */
    private void open(Intent first, Intent fallback) {
        try {
            startActivity(first);
        } catch (RuntimeException e) {
            if (fallback == null) fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(fallback); } catch (RuntimeException ignored) { }
        }
    }

    // ---------------------------------------------------------------- dialogs

    private interface TimeChosen { void chosen(int hour, int minute); }

    private void pickTime(int hour, int minute, TimeChosen then) {
        dialog = new TimePickerDialog(this, (picker, h, m) -> {
            then.chosen(h, m);
            rebuild();
        }, hour, minute, DateFormat.is24HourFormat(this));
        dialog.show();
    }

    private void confirmDelete(Alarm a) {
        dialog = new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, format(a)))
                .setPositiveButton(R.string.yes_delete, (d, w) -> {
                    store.delete(a.id);
                    Scheduler.cancel(this, a.id);
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showUnlock() {
        LinearLayout box = dialogBox();
        EditText pin = pinField(R.string.pin_hint);
        TextView error = errorText();
        box.addView(pin);
        box.addView(error);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.unlock_title)
                .setMessage(R.string.unlock_message)
                .setView(box)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog = d;

        // If nobody types for UNLOCK_IDLE_MS, close the prompt and go back to the
        // locked list, so a PIN prompt opened by accident never lingers. Each digit
        // restarts the countdown, so a slow helper is not cut off mid-PIN.
        Runnable autoClose = d::dismiss;
        handler.postDelayed(autoClose, UNLOCK_IDLE_MS);
        pin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(autoClose);
                handler.postDelayed(autoClose, UNLOCK_IDLE_MS);
            }
        });
        d.setOnDismissListener(x -> handler.removeCallbacks(autoClose));

        // Overridden after show() so a wrong PIN keeps the dialog open.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (store.unlock(pin.getText().toString())) {
                d.dismiss();
                rebuild();
            } else {
                pin.setText("");
                error.setText(R.string.pin_wrong);
            }
        });
    }

    private void showLock() {
        LinearLayout box = dialogBox();
        EditText pin = pinField(R.string.pin_hint);
        EditText repeat = pinField(R.string.pin_repeat_hint);
        TextView error = errorText();
        box.addView(pin);
        box.addView(repeat);
        box.addView(error);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.lock_title)
                .setMessage(R.string.lock_message)
                .setView(box)
                .setPositiveButton(R.string.lock, null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog = d;
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p1 = pin.getText().toString();
            if (!Pin.isValid(p1)) {
                error.setText(R.string.pin_invalid);
            } else if (!p1.equals(repeat.getText().toString())) {
                error.setText(R.string.pin_mismatch);
            } else {
                store.lock(p1);
                d.dismiss();
                rebuild();
            }
        });
    }

    private void openRingScreen() {
        try {
            startActivity(new Intent(this, RingActivity.class));
        } catch (RuntimeException e) {
            // The banner below stays as the way to stop.
        }
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        box.setPadding(p, dp(8), p, 0);
        return box;
    }

    private EditText pinField(int hintRes) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint(hintRes);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        e.setMinHeight(dp(64));
        return e;
    }

    private TextView errorText() {
        TextView t = text("", 20);
        t.setTextColor(getColor(R.color.stop_red));
        return t;
    }

    // ---------------------------------------------------------------- helpers

    private String format(Alarm a) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        return DateFormat.getTimeFormat(this).format(c.getTime());
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.text_black));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /** Solid button with white text: the main actions (ON, Add alarm, Turn off). */
    private Button filled(String label, int bgRes, int sp, int minHeightDp) {
        return pill(label, getColor(bgRes), Color.WHITE, 0, R.color.press_light, sp, minHeightDp);
    }

    /** Soft-coloured button with dark text: the resting state (OFF). */
    private Button tinted(String label, int bgRes, int fgRes, int sp, int minHeightDp) {
        return pill(label, getColor(bgRes), getColor(fgRes), 0, R.color.press_dark, sp, minHeightDp);
    }

    /** White button with a coloured outline: secondary actions (Change time, Delete, Lock). */
    private Button outlined(String label, int fgRes, int sp, int minHeightDp) {
        int fg = getColor(fgRes);
        return pill(label, getColor(R.color.card), fg, fg, R.color.press_dark, sp, minHeightDp);
    }

    private Button pill(String label, int bg, int fg, int stroke, int pressRes, int sp, int minHeightDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setMinHeight(dp(minHeightDp));
        b.setMinimumHeight(dp(minHeightDp));
        b.setStateListAnimator(null); // flat; the ripple alone shows the press
        b.setBackground(ripple(pressRes, bg, stroke, minHeightDp / 2));
        return b;
    }

    /**
     * Rounded background with the platform's ripple on press, so every tap gives a
     * visible "yes, that worked". stroke 0 = no outline.
     */
    private RippleDrawable ripple(int pressRes, int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(2), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(getColor(pressRes)), shape, mask);
    }

    /** Full width with a gap above, so big targets never touch each other. */
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        return lp;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
