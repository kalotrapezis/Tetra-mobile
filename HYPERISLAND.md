# Xiaomi HyperOS Island: what we learned

How Tetra puts its notifications in the **Hyper Island**, Xiaomi's version of the Dynamic Island on HyperOS: the pill
at the top of the screen that opens into a card. Xiaomi publishes no official guide. Everything here was found by trial
on real devices (a Xiaomi 15 phone and a Xiaomi Pad, HyperOS 2 / Android 15–16) between September and November 2026, and
by reading the open-source **HyperIsland ToolKit** and the `hyperisland_kit` library. The code is
[`HyperIsland.kt`](app/src/main/java/com/kalotrapezis/drive/HyperIsland.kt), about 170 lines with no dependencies.

If you only remember one thing: **post the notification while your app is in the background.** HyperOS gives the app
in front no island and no heads-up pop-up, so nearly every early test on our side seemed to fail.

## 1. The mechanism

There is no SDK, service or permission. You post an **ordinary notification** that works on its own. The
notification also carries a JSON string in the extra `miui.focus.param`. HyperOS reads the JSON and draws the island.
Every other phone ignores the extra.

```kotlin
builder.addExtras(Bundle().apply {
    putString("miui.focus.param", json)            // what to draw
    putBundle("miui.focus.actions", actionsBundle) // the island's buttons (optional, see 4)
})
```

Wrap all of it in `runCatching`. If anything goes wrong, the user should still get the plain notification.

We do not use `hyperisland_kit`. It does the same thing, but it declares minSdk 35 and pulls in appcompat, material and
kotlinx-serialization. Writing the JSON with `org.json` takes about 30 lines.

## 2. Is the island there?

```kotlin
fun isSupported(c: Context) = runCatching {
    Build.MANUFACTURER.equals("Xiaomi", true) &&
    SystemProperties.getBoolean("persist.sys.feature.island", false) &&   // via reflection
    c.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null,
        Bundle().apply { putString("package", c.packageName) })?.getBoolean("canShowFocus", false) == true
}.getOrDefault(false)
```

- `persist.sys.feature.island`: the device has an island. Read it through reflection on `android.os.SystemProperties`.
- `canShowFocus`: the user can turn off "focus notifications" for each app, and this provider tells you the state for
  your package. Show it in your settings and link to the app's notification settings. Tetra does this in Settings ›
  Calendar, with a "Hyper Island" status row.

## 3. The JSON (`param_v2`)

The smallest JSON that works for us. It is a progress island, as used by the sync:

```json
{
  "param_v2": {
    "protocol": 3,
    "business": "tetra_sync",
    "updatable": true,
    "ticker": "Backing up",
    "enableFloat": true,
    "isShowNotification": true,
    "baseInfo": { "type": 1, "title": "Backing up", "content": "12 of 40 photos" },
    "param_island": {
      "islandProperty": 1,
      "islandPriority": 1,
      "expandedTime": 2500,
      "smallIslandArea": { "picInfo": { "type": 1 } },
      "bigIslandArea": {
        "imageTextInfoLeft":  { "type": 1, "picInfo": { "type": 1 } },
        "imageTextInfoRight": { "type": 2, "textInfo": { "title": "12/40" } }
      }
    },
    "progressInfo": { "progress": 30, "colorProgress": "#4C8DF6" }
  },
  "title": "Backing up",
  "content": "12 of 40 photos"
}
```

The traps:

| Key | Why it matters |
|---|---|
| `protocol: 3` | **Required.** With any other value, HyperOS parses the JSON into an empty island and shows nothing. |
| `business` | A stable id for each *kind* of island. Two kinds with the same id fight over one island. We use `tetra_sync`, `tetra_analysis`, `tetra_calendar`, `tetra_timer`, `tetra_stopwatch` and `tetra_alarm`. |
| `updatable: true` | One island that changes in place, instead of a new one on every post. |
| `enableFloat` | Opens the big card. Set it **only on the first post**, or on a moment that matters (a reminder, a ring). If it is set on every post, the card pops open again with every progress step. |
| `expandedTime` | How long, in ms, the big card stays before it shrinks back to the pill. |
| `picInfo.type: 1` | Uses the notification's small icon. No bitmap needed. |
| `islandFirstFloat: true` | Used for reminders. Opens the card the first time. |

### A countdown or count-up that HyperOS runs by itself

For a timer you do not need to post every second. Put a `timerInfo` on the right side of the big island, and HyperOS
counts down (or up) on its own. This is how Xiaomi's own Clock does it:

```json
"bigIslandArea": {
  "imageTextInfoLeft": { "type": 1, "picInfo": { "type": 1 } },
  "sameWidthDigitInfo": {
    "timerInfo": { "timerType": -1, "timerWhen": 1793550000000, "timerTotal": NOW, "timerSystemCurrent": NOW }
  }
}
```

- `timerType: -1` counts **down** to `timerWhen` (epoch ms).
- `timerType: 1` counts **up** from `timerWhen`. We use it for the stopwatch.
- When the timer is paused or done, swap it for a plain `imageTextInfoRight` text such as "4:12" or "Time's up".

On the standard notification, use `setUsesChronometer(true)` with `setChronometerCountDown(true)` and `setWhen(end)`.
The shade and the island then both count without updates.

## 4. Buttons

The island's buttons are notification actions. They go in a bundle and are linked to the JSON by key:

```kotlin
val actions = Bundle().apply {
    putParcelable("miui.focus.action_done",
        Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_done), "Done", donePendingIntent).build())
    putParcelable("miui.focus.action_snooze",
        Notification.Action.Builder(null as Icon?, "Snooze 10 min", snoozePendingIntent).build())
}
// in param_v2:
"actions": [
  { "type": 0, "action": "miui.focus.action_done",   "actionTitle": "Done",          "actionIntentType": 2 },
  { "type": 2, "action": "miui.focus.action_snooze", "actionTitle": "Snooze 10 min", "actionIntentType": 2 }
]
```

- `type: 0` is a **round icon button**. HyperOS draws the action's `Icon`. This is the ✕ on Tetra's Calendar reminder.
- `type: 2` is a **text button** that shows `actionTitle`.
- `actionIntentType: 2` means the PendingIntent is a broadcast or service. Ours are `getBroadcast` and
  `getForegroundService`.
- For an icon button, use a self-contained drawable: a filled dark circle with a coloured rim and a coloured glyph. See
  [`ic_reminder_done.xml`](app/src/main/res/drawable/ic_reminder_done.xml) and `ic_timer_*.xml`. HyperOS does not tint
  it, so a plain white glyph disappears on a light background.
- Keep `actionTitle` on icon buttons too. It is the accessible name.
- In one case, HyperOS's focus card drew **only one of two buttons**: the high-priority "alarm in 30 min" notice on the
  Xiaomi 15. We post that notice as a plain notification, without the island.

### Icon-only buttons in the normal notification shade

`addAction()` buttons in Android's own template always show their **text**. Since Android 7, the icon is not drawn. To
get icons only, as on the island, give the notification its own body:

```kotlin
val v = RemoteViews(c.packageName, R.layout.timer_notification) // title, text, two ImageViews
v.setImageViewResource(R.id.button1, R.drawable.ic_timer_pause)
v.setOnClickPendingIntent(R.id.button1, pausePendingIntent)
v.setContentDescription(R.id.button1, "Pause")                  // TalkBack: there is no visible text
builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
    .setCustomContentView(v).setCustomBigContentView(v).setCustomHeadsUpContentView(v)
```

Do **not** also call `addAction()` for the same buttons, or Android adds a row of text buttons under the icons. The
island does not need `addAction()`, because it reads `miui.focus.actions`. On HyperOS, this layout shows as the focus
card in the shade, with the round icons at the right. Seen on the Xiaomi 15 on 1 Nov 2026. Code: `iconButtons()` in
[`TimerService.kt`](app/src/main/java/com/kalotrapezis/drive/TimerService.kt).

## 5. Behaviour that is not written anywhere

1. **No island for the app in front.** HyperOS shows neither the island nor the heads-up pop-up for the app the user
   is looking at. Consequences:
   - Test from the home screen. Tetra's "Send a test reminder" fires **5 seconds later** so you have time to leave.
   - Something that starts while you are in the app (a timer) must be **posted again when the app goes to the
     background**, or it never reaches the island. Tetra re-posts everything when MainActivity stops.
   - If something rings while the app is in front, show it yourself inside the app. Tetra opens a dialog with the
     same Done / Snooze.
2. **Ongoing notifications get the island.** A one-off notification that the user can dismiss usually did not. Make
   reminders `setOngoing(true)`, as Xiaomi's own Clock timer is, and cancel them yourself when they are answered.
3. **Only the first post should open the card** (`enableFloat`). After that, update the pill quietly.
4. The emulator has no island. Only a real HyperOS device shows it.

## 6. A probe for experiments

Debug builds have [`IslandProbe.kt`](app/src/debug/java/com/kalotrapezis/drive/IslandProbe.kt), a receiver guarded by
the `DUMP` permission, so only adb can trigger it:

```bash
adb shell am broadcast -a com.kalotrapezis.drive.ISLAND_PROBE --es variant reminder
```

`variant` is `sync`, `reminder`, `plain` or `clear`. Run it with the app in the background. It posts one notification
in each shape, so you can change one key at a time and see what the island does. This is how we found the "app in
front" rule.

To see what HyperOS received:

```bash
adb shell dumpsys notification --noredact | grep -A3 "pkg=com.your.app"
```

When the extra was read, the record shows `focusType=PARAMS`.

## 7. Where Tetra uses it

| What | `business` | Shape | Buttons |
|---|---|---|---|
| Sync / backup | `tetra_sync` | progress, `n/total` | none (Pause/Stop stay in the app) |
| Photo analysis | `tetra_analysis` | progress | none |
| Calendar reminder, AI briefs | `tetra_calendar` | event + time | ✕ Done (icon), Snooze 10 min (text) |
| Timer | `tetra_timer` | countdown by HyperOS | ‖ / ▶ and ×; ringing: zzz (+1 min) and × |
| Stopwatch | `tetra_stopwatch` | count-up by HyperOS | ‖ / ▶ and × |
| Alarm ringing, next alarm | `tetra_alarm` | its time | zzz (snooze 10 min) and × |

## Credits

The key names and the `param_v2` format come from the open-source HyperIsland ToolKit and `hyperisland_kit`. Their
code was read, not copied. The behaviour in section 5 is our own finding, on Tetra (GPL).
