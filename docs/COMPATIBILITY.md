# App compatibility: where the Turnypt entries appear

Turnypt integrates through three OS-standard mechanisms: the **text
selection toolbar** (`ACTION_PROCESS_TEXT`), the **sharesheet**
(`ACTION_SEND`), and the **clipboard**. Whether an entry appears inside a
particular app is decided by *that app*, not by Turnypt. This page is the
honest matrix, so nobody files a bug that cannot be fixed from here.

## Quick answer

**"I don't see Encrypt/Decrypt (Turnypt) when I select a message in
Telegram."** — Expected. Telegram draws its own text-selection menu for
message bubbles (Reply / Copy / Quote / …) and does not include third-party
`ACTION_PROCESS_TEXT` actions there. No app can add itself to that menu.
Use the clipboard flow instead — see *The recommended flow in Telegram*
below.

## Why this happens (technical)

The system text-selection toolbar is shown by standard `TextView` /
`EditText` widgets. Any app that renders text in **custom views** (Telegram
message bubbles, some WebView-based feeds) builds its own menu and would
have to query for `PROCESS_TEXT` activities and add them manually —
Telegram does not. On top of that, since Android 11 the host app must
declare package visibility (`<queries>`) to even see candidate apps, and
some hosts cap the number of third-party entries shown.

Sources: [Android Developers — custom text selection actions][1],
[PROCESS_TEXT visibility after Android 11][2].

## Matrix

| Context | Selection toolbar entry | Recommended path |
|---|---|---|
| Browsers (Chrome, Samsung Internet, Firefox) | ✅ appears (One UI 6+: in the ⋮ overflow) | select → Encrypt/Decrypt (Turnypt) |
| Standard `EditText` fields (SMS, Keep, most input boxes) | ✅ appears (overflow on One UI) | select → Encrypt/Decrypt (Turnypt) |
| Telegram — message bubbles (received text) | ❌ never (Telegram's own menu) | **copy → long-press Turnypt icon → Decrypt clipboard** |
| Telegram — message input field (draft) | ⚠️ usually, in the overflow; Telegram-controlled | select draft → Encrypt (Turnypt), or clipboard flow |
| WhatsApp — message bubbles | ❌ (own menu) | clipboard flow |
| WhatsApp — input field | ✅ usually (overflow) | select draft → Encrypt (Turnypt) |
| Apps with a Share button on text | n/a — sharesheet | Share → Decrypt with Turnypt |
| Anything else / worst case | — | copy → long-press Turnypt → **Decrypt clipboard** / **Encrypt clipboard**, or enable the floating assistant |

## The recommended flow in Telegram (zero extra taps over a toolbar)

Receiving:
1. Long-press the `TNP1:…` message in Telegram → **Copy**.
2. Long-press the **Turnypt** icon on the home screen →
   **Decrypt clipboard** (launcher shortcut, added in v0.5.0).
3. Read the plaintext preview, **Copy** if needed, close — you land back
   where you were.

Sending:
1. Type nothing yet — long-press **Turnypt** → **Encrypt clipboard**
   (or type the draft anywhere, cut it to the clipboard, then encrypt).
2. Pick the contact, unlock, **Copy** the envelope.
3. Paste into the Telegram input and send.

Power users can enable the **floating assistant** (Settings → Features):
a draggable bubble that encrypts/decrypts the clipboard over any app.

## What Turnypt will never do to "fix" this

- ❌ draw an overlay over Telegram's menu without permission,
- ❌ read the clipboard in the background,
- ❌ add network code or an account system.

The clipboard is read **only** in direct response to a tap you made,
through the audited `ClipboardAccess` chokepoint — the same rule since
v0.1.

[1]: https://medium.com/google-developers/custom-text-selection-actions-with-action-process-text-191f792d2999
[2]: https://stackoverflow.com/questions/67660035/process-text-not-visible-in-text-selection-menu-in-most-apps-after-android-11
