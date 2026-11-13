# Turnypt Design Research & the v0.5 Design Language

Date: 2026-11-06 · Status: implemented in v0.5.0-alpha
Method: full audit of the v0.4.2 UI code + Material 3 specification study +
precedent analysis (Signal, 1Password-class vault apps) + Android platform
research for the text-selection problem. Sources are linked inline.

---

## 1. What the audit of v0.4.2 found

A screen-by-screen read of `app/src/main/java/com/turnypt/app/ui/` found a
Material 3 theme that is *declared* but barely *used as a system*:

1. **Under-used color roles.** Only ~17 of the ~26 Material 3 roles were
   defined; `surfaceContainer*`, `inverse*`, `outlineVariant` and `scrim`
   fell back to library defaults, so grouping surfaces (`SectionCard`,
   contact cards, result blocks) were built from `outline` borders instead
   of the tonal elevation the system prescribes.
2. **No semantic success tone.** "Unlocked" (the single most important
   security state) rendered in the same indigo as every positive action —
   a missed signal channel (see §3.3).
3. **Chips and tiles were buttons.** `QuickActionTile` was an
   `OutlinedButton` with a 18dp icon and a one-line label — a 40dp-tall
   control for the app's four primary actions, with small touch area and
   no visual hierarchy.
4. **Settings switches were unlabeled.** A `Switch` next to the literal
   text "On"/"Off" (not the feature name) — the row reads as a status
   display, not a control.
5. **Contacts "Add" affordance was a text button** floating as a pseudo-FAB.
6. **Empty states had no call to action** (icon + title + body only), so a
   new user's first screen ended in a dead end.
7. **Inconsistent emphasis**: ad-hoc `fontSize`/`FontWeight` on several
   screens despite the v0.4.2 `Typography` scale.

## 2. Research findings

### 2.1 Material 3 color is a *role system*, not a palette

Material 3 defines **26 roles in six groups** (primary, secondary, tertiary,
error, surface, outline) with two hard rules: colors are only used in
intended **pairs** ("on-" roles on their container), and containers are
fills — never text or icon colors. The pairings are pre-checked for
≥3:1 contrast; free-form mixing silently breaks accessibility.
Source: [Color roles — Material 3](https://m3.material.io/styles/color/roles).

Implication: Turnypt should define *every* role it can (including the
surface-container tier and inverse roles) and build grouped surfaces from
tonal containers, not borders.

### 2.2 Dark theme: grey, not black; elevation = lightness

Material's dark-theme guidance: use dark **grey** surfaces (≈#121212) rather
than pure black; surfaces get *lighter* with elevation; accents are
desaturated so body text passes WCAG AA (4.5:1); color is sparse.
Source: [Dark theme — Material Design](https://m2.material.io/design/color/dark-theme.html).

Turnypt's dark neutrals (#121318/#16171C) already follow this; the
redesign keeps them and adds the container tier so elevation differences
are expressed tonally.

### 2.3 Security apps earn trust with *calm*, not decoration

Precedent analysis (Signal on Android/iOS, 1Password-class vaults):

- Signal's UI studies repeatedly note the **plain background, minimal
  chrome and quiet confidence** — security surfaces avoid playful
  gradients and heavy illustration because the subject is serious
  ([UX analysis](https://uxdesign.cc/a-week-on-the-signal-app-528efdc33b4),
  [iOS case study](https://medium.com/@moonsicles/signal-ios-case-study-4fea87589c9a)).
- Vaults (1Password, Bitwarden) use **one unmistakable "unlocked" color**
  (green-family) separate from the brand color, plus large tap targets and
  biometric-first unlock (1Password's mobile app is the canonical
  example: oversized tap targets, biometric unlock as the first path).

Implication: keep the indigo "trust" brand for actions, and introduce a
**dedicated green success tone for "unlocked/secure"** — a second, semantic
signal channel that does not compete with the brand.

### 2.4 Empty states must offer the next step

NN/g: a good empty state does three things — communicates status, educates,
and **provides a direct pathway** (CTA) — because the empty state is where
new users land first.
Source: [Designing Empty States in Complex Applications (NN/g)](https://www.nngroup.com/articles/empty-state-interface-design).

Implication: `EmptyState` gains an optional action button (Contacts empty
state → "Add contact").

### 2.5 Progressive disclosure for everything technical

Progressive disclosure reduces cognitive load by showing essentials first
and advanced controls on demand
([NN/g definition](https://www.nngroup.com/articles/progressive-disclosure/),
[13 examples](https://userpilot.com/blog/progressive-disclosure-examples)).

Turnypt's crypto knobs (Argon2 profiles, KDF strength, era internals) are
expert settings. They stay available exactly where power users look for
them, but the redesign never surfaces them on the primary path. (This also
honors the project's earlier user feedback: *no tutorial walls in the app* —
guides live in the README, not on screen.)

### 2.6 Touch targets and motion

Material accessibility baseline: ≥48dp touch targets, icon + color (never
color alone) for state, and motion that explains (no decoration). The quick
actions become 88dp-tall tiles; state chips gain leading icons so state is
readable without relying on hue (color-blind safety).

## 3. The v0.5 design language — "Calm Vault"

Three principles, each traceable to §2:

1. **Calm** — tonal surfaces instead of borders; generous spacing; nothing
   moves unless it means something. (§2.1, §2.2, §2.3)
2. **State is sacred** — lock state has its own color (green success),
   its own icon (lock/open), and its own surface treatment; it is never
   implied by prose. (§2.3, §2.6)
3. **One obvious action per screen** — the primary action is filled and
   full-width; everything else is outlined or text. (§2.5)

### 3.1 Color

- **Brand (primary): indigo** — trust/stability, unchanged in hue from
  v0.4.x, now with the full role set (`surfaceContainerLow/Lowest/High/
  Highest`, `surfaceDim/Bright`, `inverseSurface/Primary/OnSurface`,
  `outlineVariant`, `scrim`, `surfaceTint`) so grouping never needs a
  border again.
- **Semantic extension: success.** Material 3 has error but no success
  role. The design adds one tonal pair (light `#146C2E`/`#C4EED0`,
  dark `#6DD58C`/`#005224` family) used *only* for the unlocked/secure
  state and its chips — the vault-app pattern (§2.3).
- **No dynamic color.** Wallpaper-derived schemes re-tint every role and
  would re-tint the *security semantics* with it; a security tool keeps
  its state colors constant by design. (Documented decision.)

### 3.2 Type

The v0.4.2 scale is kept (it already follows M3 dimensions) and now used
everywhere — screens no longer pass ad-hoc `fontSize` for emphasis.
`labelLarge` is the button/CTA style; `titleMedium` with a leading icon
titles every section card.

### 3.3 Shape

8 / 12 / 16 / 24 / 32dp stays. New usage rules: section cards 16
(`medium`), dialogs 24+ (unchanged), status chips are **pills** (50%),
quick-action tiles 16.

### 3.4 Components (before → after)

| Component | v0.4.2 | v0.5 | Why (source) |
|---|---|---|---|
| `SectionCard` | 1dp outlined card | tonal `surfaceContainerLow`, 16dp, optional leading icon in title | role system, tonal grouping (§2.1) |
| `StatusChip` | small rectangle, text-only | pill + leading icon; success tone vs secondary tone | state channel + color-blind safety (§2.3, §2.6) |
| `QuickActionTile` | outlined button row, 18dp icon | 88dp tile: 40dp tonal icon circle + 2-line label | touch targets (§2.6) |
| `EmptyState` | icon + title + body | + optional CTA button | NN/g empty states (§2.4) |
| `NoticeBanner` | error container only | 4 tones (info/success/warning/error) + leading icon | state channel (§2.3) |
| `SettingSwitchRow` (new) | "On/Off" text + switch | titled row + summary + switch | controls must be labeled (§2.6) |
| `ContactCard` | flat list row | avatar (initial) + name + era line + state chip | vault pattern (§2.3) |
| `ResultBlock` | bare text | monospace on `surfaceContainerLowest`, 12dp padding, copy/clear row | role system (§2.1) |

### 3.5 Screens

- **Home** — brand header, one *status card* (contacts + unlocked chips),
  one *quick-actions* 2×2 grid (Encrypt/Decrypt text · Encrypt/Decrypt
  clipboard). Nothing else on screen (§2.5; also the earlier "no tutorial
  walls" feedback).
- **Contacts** — empty state with "Add contact" CTA; list of avatar cards;
  `ExtendedFloatingActionButton` for adding.
- **Encrypt / Decrypt** — same flows, restyled containers; logic untouched.
- **Settings** — four titled sections (Session & security / Features /
  Key derivation / About), labeled switch rows, "Lock all sessions" as a
  prominent error-toned button.

## 4. The messenger problem (Telegram et al.)

### 4.1 Root cause — researched, not guessed

User report: "Encrypt (Turnypt)" appears in the selection toolbar in
browsers but not in Telegram. This matches how the platform works:

1. **Custom text views build their own menus.** `ACTION_PROCESS_TEXT`
   entries appear in the *system* text-selection toolbar, which is shown
   by standard `TextView`/`EditText`. Apps that draw their own message
   views — **Telegram renders message bubbles in custom views with its
   own ActionMode menu** (Reply/Copy/Quote/…) — show third-party
   PROCESS_TEXT actions **only if they manually query and add them**,
   which Telegram does not. Browsers and most `EditText` fields do.
   Sources: [Android Developers — Custom text selection actions](https://medium.com/google-developers/custom-text-selection-actions-with-action-process-text-191f792d2999),
   [Stack Overflow: PROCESS_TEXT not visible in most apps](https://stackoverflow.com/questions/67660035/process-text-not-visible-in-text-selection-menu-in-most-apps-after-android-11).
2. **Android 11+ package visibility** made it *optional* for host apps to
   even see candidate apps (they must declare a `<queries>` intent), and
   some hosts additionally cap the number of third-party entries.
3. **Practical matrix:** Chrome/Samsung Internet and standard input
   fields → entries appear (on One UI 6+, in the ⋮ overflow). Telegram
   message bubbles → never. Telegram's message input field is a standard
   `EditText` → entries normally appear in the overflow, but this is
   Telegram-controlled and not guaranteed.

**Conclusion: no manifest change on Turnypt's side can force its entry
into Telegram's message-selection menu. The fix is a fallback ladder that
works everywhere.**

### 4.2 The v0.5 fallback ladder

1. **Launcher app shortcuts (new, zero permissions).** Long-press the
   Turnypt icon → **Decrypt clipboard** / **Encrypt clipboard**. In
   Telegram: copy the message → long-press Turnypt → Decrypt → read →
   back to Telegram. Implemented as two static shortcuts
   ([app shortcuts guide](https://developer.android.com/develop/ui/views/launch/shortcuts))
   routed through a no-display trampoline activity into the existing
   clipboard companion flows (focus-safe clipboard read, FLAG_SECURE,
   noHistory).
2. **Floating assistant (existing, opt-in)** — the bubble works over any
   app, including Telegram.
3. **Sharesheet + in-app tools (existing)** — where the host app does
   support sharing, and for manual paste.

The compatibility matrix and recommended flow per app are documented in
[`docs/COMPATIBILITY.md`](COMPATIBILITY.md) (linked from the README).

## 5. Accessibility checklist (v0.5)

- All interactive targets ≥ 48dp (quick tiles 88dp).
- State signaled by icon **and** color (chips, banners).
- Text/icon colors only ever used on their paired container roles.
- Buttons/switch rows have text labels; decorative icons are
  `contentDescription = null`.
- Dark and light schemes both hand-checked for the M3 pairings.

## 6. Deliberately not done

- **No tutorial/onboarding walls** — explicit earlier user feedback.
- **No dynamic color** — §3.1.
- **No custom fonts, illustrations or animations** — dependency-free calm;
  the icon table stays hand-built vectors.
- **No new permissions** — the shortcuts path needs none.

## References

1. Color roles — Material 3: https://m3.material.io/styles/color/roles
2. Dark theme — Material Design: https://m2.material.io/design/color/dark-theme.html
3. A week on Signal (UX analysis): https://uxdesign.cc/a-week-on-the-signal-app-528efdc33b4
4. Signal iOS case study: https://medium.com/@moonsicles/signal-ios-case-study-4fea87589c9a
5. Empty-state design — NN/g: https://www.nngroup.com/articles/empty-state-interface-design
6. Progressive disclosure — NN/g: https://www.nngroup.com/articles/progressive-disclosure/
7. Custom text-selection actions (Android Developers): https://medium.com/google-developers/custom-text-selection-actions-with-action-process-text-191f792d2999
8. PROCESS_TEXT visibility after Android 11: https://stackoverflow.com/questions/67660035/process-text-not-visible-in-text-selection-menu-in-most-apps-after-android-11
9. App shortcuts — Android Developers: https://developer.android.com/develop/ui/views/launch/shortcuts
