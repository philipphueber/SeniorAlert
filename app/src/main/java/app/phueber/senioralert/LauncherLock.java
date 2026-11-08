package app.phueber.senioralert;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

/**
 * The lock of SeniorLauncher, read through its LockProvider: when the helper
 * locks the home screen, this app is locked too (alarms cannot be added,
 * changed or deleted), with one lock for the whole phone.
 *
 * This app cannot lock it; it can only lift it with its PIN ({@link #unlock}),
 * so the helper unlocks right here instead of going back to the home screen.
 * Without SeniorLauncher installed this app keeps its own PIN lock (Store).
 *
 * Never read on the ringing path: only the alarm list asks, so a missing or
 * crashing launcher can never stop an alarm from ringing or being turned off.
 */
final class LauncherLock {
    static final String PACKAGE = "app.phueber.seniorlauncher";
    static final Uri STATE = Uri.parse("content://app.phueber.seniorlauncher.lock/state");

    private LauncherLock() {}

    /** SeniorLauncher is installed and new enough to share its lock. */
    static boolean present(Context c) {
        try {
            return c.getPackageManager().resolveContentProvider(STATE.getAuthority(), 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Asks SeniorLauncher to unlock with this PIN; true if it did. False for a
     * wrong PIN, and when the launcher cannot be asked.
     */
    static boolean unlock(Context c, String pin) {
        try {
            android.os.Bundle out = c.getContentResolver().call(STATE, "unlock", pin, null);
            return out != null && out.getBoolean("unlocked", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True if SeniorLauncher says it is locked; false if it is unlocked, absent or silent. */
    static boolean locked(Context c) {
        try (Cursor cur = c.getContentResolver().query(STATE, null, null, null, null)) {
            return cur != null && cur.moveToFirst() && cur.getInt(0) == 1;
        } catch (RuntimeException e) {
            return false; // launcher gone, or crashing: fall back to our own lock
        }
    }

    /** Whether the launcher app itself is installed at all (for the Settings text). */
    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}
