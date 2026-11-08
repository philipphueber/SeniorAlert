# Architecture

Package `app.phueber.senioralert`. Two screens: `MainActivity` (alarm list,
lock) and `RingActivity` (the ringing screen). Everything else is plumbing.
Plain Java, platform APIs only. Keep this file current with every change.

## Files

| File | Role |
| --- | --- |
| `Alarm` | Immutable one-shot alarm (id > 0, hour, minute, enabled). Pure Java, validated. |
| `AlarmCodec` | List of alarms to/from one string `id:h:m:e;...`. Skips malformed entries, drops duplicate ids, sorts by time then id. Pure. |
| `NextTrigger` | Next fire time for hour:minute in a given `TimeZone`, via `Calendar`, so 07:00 stays 07:00 across DST. Used when an alarm is switched ON: the next occurrence of its time; "now" (same minute) means tomorrow. Pure. |
| `Pin` | PIN validation (4-8 ASCII digits), salted SHA-256 hash, constant-time compare. Pure. |
| `Store` | All persistent state (alarms, next id, lock flag, PIN salt/hash) in device-protected SharedPreferences. `isLocked()` is the launcher's lock or the own PIN lock. |
| `LauncherLock` | Reads SeniorLauncher's lock (`content://app.phueber.seniorlauncher.lock/state`) and lifts it with its PIN (`call(..., "unlock", pin)`). With the launcher installed, the home screen's lock is the lock: the Lock/PIN button gives way to a note, and "Hold to unlock" asks for the home screen's PIN right here. Without it, the PIN lock works as before. Only the alarm list uses it, never the ringing path. |
| `Scheduler` | Arms/cancels one `AlarmManager.setAlarmClock` per enabled alarm (inexact `setAndAllowWhileIdle` only if exact alarms are denied). `rescheduleAll` re-arms everything from scratch (boot, time change); `ensureArmed` arms only what AlarmManager lost (app opened), so a pending, possibly late alarm is never pushed to tomorrow. |
| `AlarmReceiver` | Receives a fired alarm: marks it OFF in `Store` first (alarms do not repeat), then starts `RingService`. If Android refuses the start, it puts the alarm back ON (armed for the next occurrence) so a missed ring never shows as OFF. |
| `BootReceiver` | On boot, package replace, time or time-zone change and exact-alarm permission change: `Scheduler.rescheduleAll`. |
| `RingService` | Foreground service (mediaPlayback): sound, vibration, full-screen notification, 2-minute hard cap from the first START (a second alarm joins the ring, it does not extend it), schedules the backup stop (StopReceiver). Sound is the platform's `DEFAULT_ALARM_ALERT_URI` (works before first unlock), ringtone second, a plain tone last. Owns `start`/`stop`/`isRinging`; teardown is total and exception-safe. If `startForeground` fails it shuts down silently rather than ring with no way to stop it. |
| `RingActivity` | Ringing screen over the lock screen: one big Turn-off button, volume keys also stop. |
| `OneLineText` | A `TextView` that stays on one line and shrinks its text only as far as needed to fit its width. Used for both clock times, so a very large phone font never wraps a time ("10:30" / "AM"). The platform autosize needs a fixed height; this needs only the width. |
| `StopReceiver` | Stops ringing: target of the notification action and of the backup AlarmManager stop. |
| `MainActivity` | Alarm list, add/edit/delete, an OFF | ON switch per alarm (two halves with bells, `ic_bell.xml`/`ic_bell_off.xml`; the active half is filled and `selected`, pressing the active half does nothing), PIN set/lock/unlock (Unlock needs a long press; the PIN prompt closes after 10 s without typing), setup warnings, banner with stop while ringing, and a Back button (left arrow, `ic_arrow_back.xml`) pinned below the scrolling list that opens the home screen (`CATEGORY_HOME`), because many seniors do not know the back gesture. Opens `RingActivity` itself when ringing starts or it is opened while ringing (the system heads-up would otherwise cover the banner). Warnings that affect whether the ring screen can appear (notifications, ringing channel importance, full-screen) also show when locked. |

Pure classes have JVM tests in `app/src/test`; Android-facing classes have none.

## Firing flow

1. `Scheduler` calls `setAlarmClock(NextTrigger.next(...))` with a PendingIntent to `AlarmReceiver`.
2. `AlarmReceiver` runs at the time. It first switches the alarm OFF, so it rings once and a crash later cannot make it ring again. Disabled or deleted alarms are ignored. Switching it ON again (in `MainActivity`) re-arms it for the next occurrence.
3. It starts `RingService` in the foreground.
4. The service posts a full-screen notification, plays the sound and starts the 2-minute timer and the backup stop.
5. The system launches `RingActivity` from the full-screen intent (or the user taps the notification).

## Stop-path invariant

Ringing can always be stopped without the PIN, from at least five independent
places: the big button, the volume keys, the notification action, the banner in
`MainActivity`, and the time limit (service timer, plus a backup `AlarmManager`
broadcast to `StopReceiver` that does not depend on the service's own timer).
All paths end in the same stop routine. Do not add any step (PIN, confirmation)
to any of them.

The visible paths depend on the notification. If notifications, the ringing
channel or full-screen intents are turned off, the sound still plays but only
the banner (after unlocking the phone) and the time limit remain. That is why
those settings are warned about even when the app is locked.

## Look

Plain platform views built in code, with no layout XML and no UI library. Colours live in
`res/values/styles.xml`. `AppTheme` is a warm off-white page with dark system-bar
icons (`values-v27` adds dark navigation-bar icons). `RingTheme` is all red.
Both screens use `fitsSystemWindows`, because Android 15 draws apps edge to edge.
Buttons are rounded `GradientDrawable`s inside the platform `RippleDrawable`,
so every tap shows feedback. Filled means a main action and outlined means a
secondary action. ON/OFF is a two-part switch rather than one toggle button:
a single button labelled with the state read as an action (pressing "OFF"
turned the alarm on), while with both halves visible you press the word you
want. The ring screen's button is a clickable
`FrameLayout` that centres the Material alarm icon (`ic_alarm.xml`, a vector)
with the label. The clock sits below the button, because the system's heads-up
notification covers the top of the screen. Times use `OneLineText`, so the
largest font setting with 12-hour times still fits one line.

## Storage

One SharedPreferences file `senioralert` opened through
`createDeviceProtectedStorageContext()`. Reason: with direct boot, alarms must
be re-armed and ring after a reboot before the first unlock, when
credential-protected storage is unreadable. Receivers, service and
`RingActivity` are `directBootAware`. The data is not sensitive. The PIN is only
a hash and only guards against accidents, not attackers. `allowBackup` is off.

## Permissions

| Permission | Why |
| --- | --- |
| `USE_EXACT_ALARM` | Exact alarms for an alarm-clock app (auto-granted API 33+). |
| `SCHEDULE_EXACT_ALARM` (max SDK 32) | Same for API 31-32. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm still-ON alarms after reboot and similar events. |
| `POST_NOTIFICATIONS` | Ringing notification (API 33+). |
| `USE_FULL_SCREEN_INTENT` | Show `RingActivity` over the lock screen. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `RingService` plays the alarm sound. |
| `WAKE_LOCK` | Keep the device awake while ringing. |
| `VIBRATE` | Vibration while ringing. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let the app ask for a battery exemption so alarms are not delayed. |

## Deliberate non-features

- No snooze: an alarm rings once and then switches itself OFF.
- No labels, no weekday selection: every alarm is one time, ringing once at its next occurrence. No repeating alarms.
- No dependencies: no AndroidX, Kotlin or libraries; fewer things to break and to update.
- No PIN recovery: clearing app data is the reset.
