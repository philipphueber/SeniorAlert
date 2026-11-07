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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
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
            if (ringing && !shownRinging) dismissDialog();
            if (ringing != shownRinging
                    || !AlarmCodec.encode(store.alarms()).equals(shownAlarms)) rebuild();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cheap and self-healing: alarms are lost e.g. after a force-stop.
        Scheduler.rescheduleAll(this);
        maybeAutoRequestNotifications();
        rebuild();
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
            Button stop = button(getString(R.string.ringing_banner) + "\n"
                    + getString(R.string.turn_off), R.color.stop_red, 36, 140);
            stop.setOnClickListener(v -> { RingService.stop(this); rebuild(); });
            root.addView(stop, spaced());
        }

        if (!locked) addWarnings();

        TextView title = text(getString(R.string.title_alarms), 34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, spaced());

        List<Alarm> alarms = store.alarms();
        shownAlarms = AlarmCodec.encode(alarms);
        if (alarms.isEmpty()) root.addView(text(getString(R.string.no_alarms), 24), spaced());
        for (Alarm a : alarms) addRow(a, locked);

        if (locked) {
            // Small and plain on purpose: it is for the caregiver, not the senior.
            Button unlock = new Button(this);
            unlock.setText(R.string.unlock);
            unlock.setAllCaps(false);
            unlock.setBackgroundColor(Color.TRANSPARENT);
            unlock.setTextColor(getColor(R.color.off_grey));
            unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(48);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            unlock.setOnClickListener(v -> showUnlock());
            root.addView(unlock, lp);
        } else {
            Button add = button(getString(R.string.add_alarm), R.color.on_green, 28, 80);
            add.setOnClickListener(v -> pickTime(12, 0, (h, m) -> Scheduler.schedule(this, store.add(h, m))));
            root.addView(add, spaced());
            Button lock = button(getString(R.string.lock), R.color.text_black, 24, 64);
            lock.setOnClickListener(v -> showLock());
            root.addView(lock, spaced());
        }
    }

    private void addRow(Alarm a, boolean locked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        row.setPadding(p, p, p, p);
        GradientDrawable border = new GradientDrawable();
        border.setStroke(dp(2), getColor(R.color.off_grey));
        border.setCornerRadius(dp(12));
        row.setBackground(border);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = text(format(a), 56);
        top.addView(time, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button toggle = button(getString(a.enabled ? R.string.state_on : R.string.state_off),
                a.enabled ? R.color.on_green : R.color.off_grey, 28, 72);
        toggle.setMinWidth(dp(120));
        toggle.setOnClickListener(v -> {
            Alarm changed = a.withEnabled(!a.enabled);
            store.update(changed);
            Scheduler.apply(this, changed);
            rebuild();
        });
        top.addView(toggle);
        row.addView(top);

        if (!locked) {
            Button change = button(getString(R.string.change_time), R.color.text_black, 22, 64);
            change.setOnClickListener(v -> pickTime(a.hour, a.minute, (h, m) -> {
                Alarm changed = a.withTime(h, m).withEnabled(true); // picking a time means "ring then"
                store.update(changed);
                Scheduler.apply(this, changed);
            }));
            row.addView(change, spaced());
            Button delete = button(getString(R.string.delete), R.color.stop_red, 22, 64);
            delete.setOnClickListener(v -> confirmDelete(a));
            row.addView(delete, spaced());
        }
        root.addView(row, spaced());
    }

    // ---------------------------------------------------------------- warnings

    private void addWarnings() {
        String pkg = getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        NotificationManager nm = getSystemService(NotificationManager.class);

        if (!notificationsAllowed()) {
            warning(R.string.warn_notifications, v -> fixNotifications());
        }
        if (!Scheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            warning(R.string.warn_exact, v -> open(new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri), null));
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            warning(R.string.warn_full_screen, v -> open(new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri), null));
        }
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg)) {
            warning(R.string.warn_battery, v -> open(
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        }
    }

    private void warning(int textRes, View.OnClickListener onClick) {
        TextView w = text(getString(textRes), 20);
        int p = dp(16);
        w.setPadding(p, p, p, p);
        w.setMinHeight(dp(64));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.warn_yellow));
        bg.setCornerRadius(dp(12));
        w.setBackground(bg);
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

    private Button button(String label, int colorRes, int sp, int minHeightDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setMinHeight(dp(minHeightDp));
        b.setMinimumHeight(dp(minHeightDp));
        b.setStateListAnimator(null); // no elevation shadow: flat and plain
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(colorRes));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
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
