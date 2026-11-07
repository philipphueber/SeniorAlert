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
| `Store` | All persistent state (alarms, next id, lock flag, PIN salt/hash) in device-protected SharedPreferences. |
| `Scheduler` | Arms/cancels one `AlarmManager.setAlarmClock` per enabled alarm; `rescheduleAll` re-arms everything. |
| `AlarmReceiver` | Receives a fired alarm: marks it OFF in `Store` first (alarms do not repeat), then starts `RingService`. |
| `BootReceiver` | On boot, package replace, time or time-zone change and exact-alarm permission change: `Scheduler.rescheduleAll`. |
| `RingService` | Foreground service (mediaPlayback): sound, vibration, full-screen notification, 2-minute timer, schedules the backup stop (StopReceiver). Owns `start`/`stop`/`isRinging`; teardown is total and exception-safe. If `startForeground` fails it shuts down silently rather than ring with no way to stop it. |
| `RingActivity` | Ringing screen over the lock screen: one big Turn-off button, volume keys also stop. |
| `StopReceiver` | Stops ringing: target of the notification action and of the backup AlarmManager stop. |
| `MainActivity` | Alarm list, add/edit/toggle/delete, PIN set/lock/unlock, permission hints, banner with stop while ringing. |

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
