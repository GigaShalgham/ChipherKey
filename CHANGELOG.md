# Changelog

All notable changes to Turnypt (formerly CipherKey) are documented in
this file. Entries up to 0.6.0 were written under the project's former
name and keep it — history is not rewritten.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-11-13 — Image extensions, unlock fixes, navigation fixes

The four user reports on the 1.1.0 release, each traced to its root cause
and fixed at the source.

### Changed

- **Encrypted images keep their real image extension** (user report:
  "images are still saved or exported with the .cki extension — image
  files must use the correct image file extension"): the Encrypt tab and
  the companion sheet now suggest the SOURCE's own name for the encrypted
  output (`photo.jpg` → `photo.jpg`; an extension-less name derives it
  from the advertised MIME), the SAF create dialog is an image create, and
  the finished file shares with the matching image MIME so messengers
  treat it as an image attachment. What is encrypted is decided by
  CONTENT — the CKF1 magic — never by the name. Sources that are not
  recognizable as images keep the legacy `<name>.cki` +
  `application/x-cipherkey` output, and every `.cki` file ever written
  keeps opening exactly as before.
- **Turnypt is registered as an opener for the supported image types**
  (user report: "Turnypt must be the default application for opening
  files with the supported image extension"): `ACTION_VIEW` intent
  filters (content/file, typed and extension-matched, mirroring the
  proven .cki shapes) make the OS offer Turnypt — and let the user set it
  as default — for jpg/jpeg/png/webp/gif/bmp/heic/heif. Opening an
  encrypted image lands straight in the decrypt flow (magic sniff); a
  normal image gets an honest "not encrypted by Turnypt" notice plus an
  "Encrypt this image instead" button, so the registration never dead-
  ends. Sharing an image INTO the app sniffs the magic too: encrypted →
  decrypt, plain → encrypt (the old name-based heuristic misrouted
  camouflaged files into the encrypt flow).

### Fixed

- **The password unlock bricked the Encrypt tab** (user report: "the
  password input does not function correctly"): on a standalone open (no
  text and no image queued), the unlock's success path never reset the
  `busy` flag — after typing the passphrase the Encrypt button stayed
  disabled forever. Both unlock paths (passphrase and fingerprint) now
  reset it; the continuations set it again themselves.
- **A finished draft could re-encrypt itself later**: the pending text
  was never consumed after a successful encrypt/decrypt, so a later
  unlock of a locked contact silently re-ran the old message. Drafts are
  consumed on completion, and switching contacts discards any queued
  continuation (it belonged to the old contact).
- **The fingerprint button only appeared after extra taps** (user report:
  "it shows up only after opening the list, clicking a contact and
  another loading cycle"): the initial screen load restored the sticky
  contact but never armed the unlock card — only an explicit contact
  click computed the fingerprint offer. `refresh()` now arms the card
  (pending state, honest suggestion gate, fingerprint offer) on first
  appearance. The Encrypt tab's unlock card also moved into its own
  titled section — the same shape the Decrypt tab uses — so the
  passphrase field, strength hint, suggestion and fingerprint button
  always render as one complete, stable card.
- **The bottom navigation bar never highlighted Encrypt/Decrypt and taps
  looked dead** (user report: "clicking does not consistently work; the
  bar fails to reflect the active page"): those two destinations live in
  the nav graph under query-pattern routes (`encrypt?mode={mode}`), and
  the selected-state check compared the bare tab route against the full
  pattern — never a match, on any screen. Selection now matches on the
  base route, and tapping the tab you are on resets any deep-link
  arguments to the tab's default state (a visible response instead of an
  apparent no-op).

## [1.1.0] - 2026-11-13 — TNP1:/TNS1: wire prefixes, contact-open fixes

The user's two reports on top of the 1.0.0 rebrand, plus the prefix
migration they asked for.

### Changed

- **Wire prefixes moved off the retired brand** (user rule: "CK1 was for
  CipherKey; now it must be TNP — the app should recognize old and new by
  itself, with no explanation to the user"): text envelopes are now
  written `TNP1:` and session invites `TNS1:`. Legacy `CK1:` / `CKS1:`
  messages and invites still decode everywhere — detection is by prefix,
  automatic, and invisible. The byte layout behind the prefix (including
  the keyId that resolves the contact and its friend era) is unchanged,
  so a legacy envelope walks the identical path; the golden vector keeps
  its body and only the written prefix moved (the legacy twin is covered
  by tests). User-visible hints moved with it (EN + FA strings: decrypt
  hints, clipboard-history tag, invite help, the About card).
- Deliberately UNCHANGED (invisible bytes inside files, renamed buys
  nothing): the binary `CKF1` magic, the `"CipherKey-CKF1-v1"` HKDF info
  string, the `.cki` extension and the `application/x-cipherkey` MIME
  type — every .cki file ever written keeps opening.

### Fixed

- **Opening a contact with the password did nothing** (user report: "the
  password shortcut in the encryption section doesn't work"): selecting a
  LOCKED contact in the Encrypt tab showed the unlock card, but submitting
  the passphrase hit a `pendingText == null` guard (no text and no image
  operation queued) and silently returned. The unlock now stands on its
  own: the contact opens, the card swaps for the Encrypt button, and the
  Remember-key offer fires, so the next unlock can be a fingerprint.
- **No fingerprint when opening a contact** (user report: "not in the
  encryption and decryption sections, and outside the app on the floating
  assistant"): the fingerprint offer is now computed the moment a locked
  contact is selected (Encrypt tab picker, companion prepare card), and
  the companion prepare card — the sheet the floating assistant opens
  over other apps — renders the "Use fingerprint" button it never had.
  The image flows share the same unlock card and benefit automatically.

## [1.0.0] - 2026-11-13 — the Turnypt rebrand: new name, new mark, .cki opens anywhere, assistant stays in context

CipherKey is now **Turnypt** — turnip + encrypt. The identity changes;
the security architecture, the wire formats and every existing message
and file do not: `CK1:` envelopes, `CKS1:` invites and `.cki` files
(including the `application/x-cipherkey` MIME and the
`CipherKey-CKF1-v1` HKDF info string) are byte-identical across the
rename, so a Turnypt user and a CipherKey 0.x user decrypt each other's
traffic without any migration.

### Added

- **Tapping a `.cki` file now suggests Turnypt in any file manager**
  (user report: "it doesn't suggest the app — and it's the only app
  that can read the file"). File managers cannot know a custom MIME
  type, so for the unregistered `.cki` extension they send
  `application/octet-stream` — or no type at all — and the v0.6.0
  MIME-only `ACTION_VIEW` filter never fired. The image companion now
  ALSO matches by extension via `pathPattern=".*\\.cki"` in the four
  shapes real file managers produce: `content://`/`file://` URIs, each
  typed and untyped. `ACTION_SEND` (sharesheet) gets the same treatment,
  so a `.cki` can be shared INTO the app too.
- **New launcher icon: the turnip mark** — cream bulb, three green
  leaves, a keyhole cut out of the bulb's face (turnip + encrypt), on a
  deep aubergine field. Ships with a monochrome layer for themed icons
  (Android 13+); all geometry verified inside the adaptive-icon safe
  zone (max radius 29.3 of 33).

### Changed

- **Rebrand CipherKey → Turnypt**, project-wide: app name (English and
  the Persian translation), package `org.cipherkey` → `com.turnypt`
  (applicationId, namespaces, source tree, manifest action/extra
  identifiers, shortcuts, Room schemas path), themes, DataStore and
  database file names, Keystore aliases, the CI artifact name, all
  documentation, and the GitHub repository itself. Protocol identifiers
  listed above are deliberately untouched.
- **The floating assistant no longer hijacks the screen** (user report:
  "tapping en/decrypt closes my app and switches to the full app").
  Companion sheets launched with `FLAG_ACTIVITY_NEW_TASK` resolved to
  the app's EXISTING task — dragging the main screen in front of the
  messenger behind a translucent sheet. Every companion activity now
  declares `taskAffinity=""`: the sheet runs in its own featherweight
  task stacked over whatever the user is doing, the messenger stays
  visible behind it, and finishing returns to the exact spot in the
  chat. Sharesheet and PROCESS_TEXT entries inherit the same fix.
- Version identity: `0.6.0` / versionCode 14 → `1.0.0` / versionCode 15.

### Upgrade notes

- The application id changed with the rename (`org.cipherkey` →
  `com.turnypt`), so Android treats Turnypt as a NEW app: install fresh
  and re-create contacts by exchanging `CKS1:` invite codes again
  (local-first means no export channel exists by design), then
  uninstall the old CipherKey app. Existing `CK1:` messages and `.cki`
  files remain fully readable.

## [0.6.0] - 2026-11-13 — second stable release: image encryption, ChaCha20-Poly1305, Automatic cipher

Promoted from the 0.6.0-alpha candidate after on-device validation:
identical features, no code change beyond the version identity. The
second full (non-pre-) release — and the first stable build to carry
the 0.5.x line: the Calm Vault design language with launcher fallback
shortcuts (0.5.0), the fingerprint / 256-MiB / Persian fixes batch
(0.5.1), clipboard history with entry-time passphrase suggestion
(0.5.2), plus the 0.6.0 headline: natively integrated image
encryption, ChaCha20-Poly1305 and the Automatic cipher preference.

### Changed

- Version identity: `0.6.0-alpha` / versionCode 13 → `0.6.0` / versionCode 14.
- README release badge and checksum example now point at v0.6.0.

## [0.6.0-alpha] - 2026-11-13 — image encryption, native; ChaCha20-Poly1305; Automatic cipher

The headline: **image encryption is no longer a side feature — it is part
of the CipherKey ecosystem.** Same contacts, same key eras, same
passphrase (and fingerprint) unlock, same visual language, same entry
points. Plus the long-requested second cipher with honest, practical
guidance and an Automatic mode that measures instead of guessing.

### Added

- **Image encryption & decryption, natively integrated**
  (user direction: "it must feel like it was always a core capability").
  - **CKF1 container format** (`.cki`, `application/x-cipherkey`): the
    exact CK1 55-byte header + independently sealed 64 KiB chunks. A
    per-file subkey (`HKDF-SHA256(eraKey, salt=nonce)`) makes chunk
    nonces collision-free across files; chunk AAD binds header,
    position and finality (reordering / splicing / truncation all
    fail authentication). Peak memory is ONE chunk — the 256 MiB OOM
    lesson of v0.5.1, now by construction. Full design:
    `docs/IMAGE-CRYPTO.md`, wire spec: `docs/PROTOCOL.md` §CKF1.
  - **The SAME trust system**: encrypt an image for the SAME contact
    with the SAME passphrase; ONE unlock serves text and files. Old-era
    `.cki` files unlock through the same historical-era path as old
    messages. No parallel contacts, no second key store.
  - **Information architecture**: a third row in Home Quick Access
    (Encrypt/Decrypt image); a Text | Image segment switch inside BOTH
    the Encrypt and Decrypt tabs; sharesheet entries ("Encrypt image"
    for image shares, "Decrypt .cki file" for our MIME type); an
    "Encrypt image" row in the floating assistant; in-sheet SAF
    pickers (no storage permission). Results: saved `.cki` with
    share-back; decrypted images preview (downsampled, private cache)
    with Save/Share via SAF/FileProvider.
- **ChaCha20-Poly1305 (RFC 8439) as the second CK1 cipher** (wire id
  `0x02`, additive — old builds reject it by design, so no protocol
  version bump). Implemented through BouncyCastle's lightweight API
  (already a dependency for Argon2id — zero new dependencies, no JCA
  provider insertion, one implementation on every API level and in JVM
  tests). Pinned byte-for-byte to the RFC 8439 §2.8.2 AEAD vector.
- **Cipher preference with practical explanations + Automatic**
  (user direction: "not just two options — explain the difference").
  Settings → Unlock & security → Cipher offers Automatic /
  AES-256-GCM / ChaCha20-Poly1305, each with a one-line practical
  description, plus an expandable "Which one should I choose?"
  explainer (hardware AES vs constant-time software, security
  equivalence, compatibility). **Automatic micro-benchmarks both
  ciphers once per process and uses the faster** — a real measurement
  of this device, not a CPU-name guess — and shows what it resolved
  to. The same dialog is reachable from the cipher chip at the point
  of use in the image flow. Decryption always follows the wire: mixed
  AES/ChaCha traffic decrypts transparently under any preference.

### Changed

- `ProtocolHeader`/`PassphraseCryptoProvider` are algorithm-aware; the
  CK1 header layout and every existing AES envelope are byte-identical
  (golden vector unchanged).
- `MessageCipher` (text) and the new `MediaCipher` (files) both draw
  the cipher for NEW encryptions from one injected preference source —
  one setting, every surface, text and files alike.

### Tests

- 440 unit tests (was 407): RFC 8439 §2.8.2 vector; RFC 5869 HKDF
  vectors (TC1/TC2); CKF1 roundtrips on every chunk boundary (empty,
  ±1 byte, exact multiples, multi-chunk) and the adversarial set
  (tamper header/chunk, truncation mid-file and sentinel-stripped,
  chunk reordering, trailing garbage, wrong key, unknown cipher id);
  `MediaCipher` two-device roundtrip over the same eras/sessions as
  text; Automatic selector outcome/caching; image naming/formatting
  helpers.

## [0.5.2-alpha] - 2026-11-07 — clipboard history, panel close, passphrase suggestion

Three clarifications from the same user who reported the v0.5.1 batch.

### Added

- **Clipboard history** (user request: "the list of things you copied and
  copied before") — the "Clipboard" button next to Paste (Encrypt and
  Decrypt tabs) now opens a HISTORY dialog instead of a single-clip
  preview: tap any entry to insert it, per-entry ✕ delete, "Clear
  history". Entries carry a relative age, a CK1 tag for envelopes and a
  "truncated" marker.
  - Capture points, all focus-gated or user-initiated (the app still
    registers NO clipboard listener and reads NOTHING in the background —
    Android 10+ would deny it anyway): the clipboard read when CipherKey
    gains window focus (copy in the messenger, then open the app), the
    assistant's Encrypt/Decrypt-clipboard rows, Paste, and the copies the
    app itself makes (envelopes, invite codes).
  - PRIVACY: decrypted plaintext and suggested passphrases copy through
    the SENSITIVE path and are NEVER recorded (the recorder marks them
    seen so a later focus read cannot pick them up either). Storage is
    capped — 25 entries, 7 days, 2 000 chars per entry — and lives in the
    private, non-backed-up database. A Settings toggle (default ON) turns
    capture off, with a "Clear history" action.
- **Strong-passphrase suggestion AT the password-entry moment** (user
  request) — every passphrase field (Encrypt/Decrypt tabs, the companion
  sheets, the contact detail dialog) can now offer "Suggest a strong
  passphrase" with Use / New one / Copy / Hide. "Use" fills the field;
  "Copy" takes the sensitive clipboard path and never enters the history.
  - GATED BY THE FRIEND INVITE (user's rule): the affordance appears ONLY
    when this era was created locally — i.e. no friend invite code was
    entered, so THIS side is choosing the shared passphrase right now —
    AND the era was never unlocked on this device. When a friend invite
    was entered, the peer already picked the passphrase the user must
    type, so no suggestion is offered. A new `session_keys.imported`
    column (DB v2 migration) records how each era arrived; after the
    first successful unlock the suggestion disappears for good.
- **Floating assistant panel ✕** (user report: "you can't just close or
  minimize it — you must do something or turn it off entirely") — the
  panel now has a header with a ✕ that COLLAPSES it back to the bubble
  without running anything; the service keeps running. Previously the
  only ways out of an open panel were performing an action or "Turn off".

### Changed

- The "View clipboard" button is renamed "Clipboard" and opens the
  history dialog; the v0.5.1 single-clip preview dialog is gone (the
  current clip is recorded on open and sits on top of the list).

### Fixed

- Suggested passphrases copied from the Add-contact dialog previously
  went through the PLAIN clipboard path (visible preview, and they would
  have been recorded by the new history) — they now use the sensitive
  path like every other secret CipherKey copies.

## [0.5.1-alpha] - 2026-11-06 — user-reported fixes batch

All six items below come from one user test pass on v0.5.0-alpha.

### Fixed

- **Fingerprint unlock never worked anywhere (HIGH).** Three stacked
  causes, all fixed:
  - `androidx.biometric` 1.1.0 (2021) silently failed to show the system
    prompt with `BIOMETRIC_STRONG` + `CryptoObject` on several OEM builds
    of Android 12+ — upgraded to the maintained **1.2.0-alpha05** line.
  - The **Remember-key offer was hidden behind the Settings toggle**
    (default off), so after a passphrase unlock the fingerprint was never
    offered anywhere unless the user had found the toggle first. The
    system prompt itself ("Remember this key" / "Not now") is now the
    consent: the offer appears after every passphrase unlock on a capable
    device, and success persists the toggle ON. Turning the toggle OFF
    still wipes every wrap — semantics unchanged.
  - Failures were **silent or generic**. The prompt helper now runs a
    `canAuthenticate(BIOMETRIC_STRONG)` pre-flight before even building
    the prompt (localized reason via `BiometricDiagnostics.reasonRes`),
    and non-cancel authentication errors surface the system's own message
    in the notice ("Fingerprint unlock did not complete (…) — use the
    passphrase."). Cancels stay quiet on purpose.
- **256 MiB encryption crashed the app (HIGH).** Two distinct OOM paths:
  - The PARANOID Argon2id preset (256 MiB) allocates one contiguous
    256 MiB `int[]`; on a stock heap (128–256 MB) that threw
    `OutOfMemoryError` through the unlock path and killed the process.
    A pre-flight heap check (`Argon2MemoryGuard`, 1.25× headroom) now
    rejects impossible profiles BEFORE allocating, the repository maps
    the typed `KdfMemoryException` to a new
    `UnlockOutcome.MemoryHungry`, and the UI answers with a localized
    "this era needs N MiB — pick a lower strength" notice. The manifest
    also gains `largeHeap` so capable devices can actually run the
    preset.
  - A multi-hundred-KiB/MiB paste/share/selection was size-checked by
    first materialising its **full UTF-8 encoding** (`toByteArray`) —
    itself the crash. Both `ProcessTextContract` and
    `MessageCipher.encryptDraft` now reject on the cheap character count
    first (a UTF-8 byte is never shorter than a char) and never allocate
    the encoding of an oversized input.

### Added

- **Persian (فارسی) translation** — a complete `values-fa` string set for
  every user-visible string; RTL was already supported
  (`supportsRtl`). Persian digits come from locale-aware formatting.
- **View clipboard** button next to Paste (Encrypt and Decrypt tabs): a
  bounded preview dialog (first 400 chars, total length, CK1-envelope
  detection, monospace for envelopes) so you can see WHAT the clipboard
  holds before pasting it.
- **Floating assistant panel, rebuilt** (it "was too weak, no options"):
  labeled rows instead of three bare icons — **Encrypt clipboard**,
  **Decrypt clipboard**, **Lock all sessions** (wipes every in-memory
  key, confirmed by a toast), **Open CipherKey**, **Turn off assistant** —
  plus an in-messenger hint line ("copy the message, then tap Decrypt
  clipboard"). The panel still never reads the clipboard itself (Android
  10+ denies that to unfocused windows; no app can read another app's
  selection in Telegram's custom menu — docs/COMPATIBILITY.md).

### Changed

- **Settings re-ordered and slimmed**: the everyday **Features** section
  now leads the screen; the session-timeout card ("lock session time")
  lost its pinned top slot and shrank from seven 40dp radio rows plus a
  full-width 48dp button to a single chip row plus a compact lock button
  (user feedback: "not very necessary so unpin it from top and make it
  smaller").

## [0.5.0-alpha] - 2026-11-06 — the "Calm Vault" redesign + messenger fallback shortcuts

### Added

- **Design research + the v0.5 design language** (`docs/DESIGN.md`): a full
  audit of the v0.4 UI against the Material 3 role system, dark-theme
  guidance, vault-app precedents and NN/g empty-state / progressive-
  disclosure research, distilled into the "Calm Vault" system — implemented
  across every screen.
- **Launcher app shortcuts (zero permissions)**: long-press the CipherKey
  icon for **Encrypt clipboard** / **Decrypt clipboard**. This is the
  universal fallback for messengers whose text-selection menu hosts no
  third-party entries — Telegram's message bubbles (and WhatsApp's) draw
  their own menu, so the selection-toolbar entry can never appear there;
  the recommended flow becomes copy → long-press CipherKey → Decrypt
  clipboard. Documented honestly with sources in `docs/COMPATIBILITY.md`.
- Settings gains a **Features** section; the empty Contacts screen gains a
  direct **Add contact** call to action (NN/g empty-state guidance).

### Changed — the v0.5 design system

- **Color**: the full Material 3 role set is now declared (the
  surface-container tier, inverse roles, `outlineVariant`, `scrim`,
  `surfaceTint`); grouped surfaces are tonal instead of bordered. A
  **SUCCESS semantic extension** (green) becomes the dedicated
  "unlocked/secure" channel — the vault-app pattern — so the brand indigo
  never has to mean two things. No dynamic color, by design: state colors
  stay constant.
- **Components**: `SectionCard` is tonal with an optional leading icon;
  `StatusChip` is a pill with a leading state icon (readable without
  color); `QuickActionTile` is an 88dp tile with a 40dp icon circle (was a
  40dp outlined button row); `EmptyState` gained a CTA; `NoticeBanner`
  gained INFO/SUCCESS/WARNING/ERROR tones so confirmations stop reading as
  errors; new labeled `SettingSwitchRow` (the old row showed the literal
  text "On"/"Off" next to the switch); new `ContactAvatar`; `ResultBlock`
  renders on a tonal inset.
- **Screens**: Home (brand header, status card, 2×2 action grid — still no
  tutorial walls), Contacts (avatar cards, extended FAB, success-tone
  feedback), Settings (four titled sections, prominent error-toned "Lock
  all sessions now"), Encrypt/Decrypt (same flows, restyled; the ad-hoc
  `fontSize` emphasis is gone), ContactPicker (lock-state icons instead of
  prepended words).
- **Passphrase strength**: STRONG now renders in the success green (FAIR
  keeps the warm ochre, WEAK stays red).

### Security

- No new permission, no network code, no schema or wire change. The
  shortcuts route through the existing audited clipboard companion flows
  (focus-deferred read, `ClipboardAccess` chokepoint, `FLAG_SECURE`,
  `noHistory`).

## [0.4.2] - 2026-11-06 — first stable release

Promoted from the 0.4.2-alpha candidate: identical features and fixes, no
code change beyond the version identity. This is the first release
published as a full (non-pre-) release, marking the repository's public
launch.

### Changed

- Version identity: `0.4.2-alpha` / versionCode 8 → `0.4.2` / versionCode 9.

### Repository

- Prepared for public release: README rewritten as a product document,
  issue/PR templates generalized, internal engineering artifacts (working
  specs, Spec Kit tooling, session reports, reassessment draft) removed
  from the tree. Git history is preserved.

## [0.4.2-alpha] - 2026-11-06 — deep-scan fixes: reactive re-lock, era-change resync, honest errors

Second on-device follow-up: a deep scan of the v0.4.1 UI found two HIGH
stale-state bugs, two MEDIUM honesty bugs and a set of LOW polish items.
All addressed; no schema, permission or wire change.

### Fixed

- **HIGH (N1) — the UI forgot to re-lock.** The in-app Encrypt/Decrypt tabs
  and the Contacts screen snapshotted "who is unlocked" once and never
  re-read it: after "Lock all sessions now" (Settings), a session timeout
  or the 60-second expiry sweeper, the Encrypt tab kept showing its Encrypt
  button with NO unlock card until the user tapped something. `KeySessionManager`
  now exposes a monotonic `sessionChanges` StateFlow (bumped on every
  activate/lock/lockAll/expiry), `SessionKeyRepository` an `eraChanges`
  counter, and `MessageCipher` combines both into one `sessionChanges`
  signal that Tools/Contacts/Companion ViewModels collect to re-read the
  live unlock state. Locking anywhere now re-locks every visible surface
  immediately.
- **HIGH (N2) — stale "unlocked" + wrong fingerprint offer after an era
  change.** A companion sheet (selection toolbar / sharesheet / floating
  assistant) kept claiming its contact was unlocked after the contact's key
  era rotated, and kept offering a fingerprint wrap bound to the OLD era.
  The sheet now resyncs on every session/era change; an offer whose wrap no
  longer matches the current era is retracted instead of dead-tapping; and
  the prompt's "contactId:eraId" key is re-checked when the sensor confirms,
  so a wrap of a rotated era can never be activated as the new era's key
  (it is discarded with the honest "out of date" notice).
- **MEDIUM (N3) — the Add dialog masked the real error.** The dialog
  discarded the ViewModel's detailed failure ("This invite key already
  belongs to another contact", "Invalid invite code: …") and printed a
  generic "Creation failed". The detailed notice now flows through as a
  typed `UiNotice` rendered from string resources.
- **MEDIUM — wrong "no key era" count for brand-new contacts.** A just-created
  contact's card flashed "no key era" until the async era query landed. The
  map is now seeded synchronously from the era that `createContact` just
  created or imported.
- **LOW — hardcoded English strings** in ContactsViewModel feedback/errors
  and the companion contact dropdown's lock glyphs are now string resources.

### Security

- **FLAG_SECURE on MainActivity** (the companion activities already had it):
  decrypted plaintext and passphrase fields can no longer leak through
  screenshots or the recents preview of the main app.

### Changed

- **Theme**: a deliberate type scale (`Typography`) joins the v0.4.1 color
  schemes and shapes — same titles/body/label weights everywhere instead of
  per-screen `fontSize` guesses.
- **Components**: `SectionCard` is outlined (1dp `outline` border, no drop
  shadow); `StatusChip` uses distinct container roles (positive =
  `primaryContainer`, informational = `secondaryContainer` — the two Home
  chips no longer blur together); `NoticeBanner` is a real banner
  (`errorContainer` background) instead of a bare error-colored line;
  `QuickActionTile` uses a real 8dp gap instead of two literal spaces
  inside the label text.
- **EmptyState** (icon + title + body on a tonal surface) replaces the bare
  left-aligned text pairs on the Contacts and Encrypt screens.
- **Rotation resilience**: the Encrypt/Decrypt input fields are
  `rememberSaveable` — a rotation no longer wipes the typed draft or the
  pasted envelope. Passphrase fields are deliberately NOT saved: view state
  must never outlive the interaction that typed it.

### Compatibility

- No database, preference, permission or CK1 wire-format change;
  versionCode 8. Old-key-era decryption, the passphrase advisor and the
  biometric gate behave exactly as in 0.4.1-alpha.

## [0.4.1-alpha] - 2026-11-06 — device-feedback fixes: design, tutorials, passphrase suggestion, biometrics

Four pieces of on-device feedback against v0.4.0-alpha, all addressed.

### Fixed

- **The recommended passphrase read as compulsory** (user feedback): the
  v0.4.0 new-contact dialog auto-showed a monospace "Recommended passphrase"
  the moment the invite field was blank — which looked like a
  system-assigned password, and taken at face value on two phones would
  give each side a DIFFERENT passphrase (they could then never decrypt each
  other's messages). The card is gone. What stays: one rule line ("Both
  people must enter the SAME passphrase — agree on it in person") and an
  OPTIONAL suggestion behind an explicit "Suggest a strong passphrase
  (optional)" button, labeled with the in-person sharing warning.
- **Biometric gate/prompt/key class mismatch**: the gate and the system
  prompt accepted `WEAK|STRONG` while the Keystore wrap key is created with
  `AUTH_BIOMETRIC_STRONG` — a Class 2 sensor passed the gate, was offered
  the prompt, and could then never authorize the cipher. Gate, prompt and
  key now all agree on STRONG (`canAuthenticate(BIOMETRIC_STRONG)`,
  `setAllowedAuthenticators(BIOMETRIC_STRONG)`).
- **Silent biometric failures**: the Settings row guessed "No fingerprint
  enrolled" for every failure. It now maps the raw `canAuthenticate` result
  to an honest reason (nothing enrolled / no sensor / sensor busy /
  pending security update / not Strong-class) — `BiometricDiagnostics`,
  pinned by unit tests (symbolic mapping, so a library renumbering cannot
  change the meaning).
- **Fingerprint affordance missing where users look for it**: "Use
  fingerprint" now also appears in the contact detail dialog (the natural
  place), with in-dialog notices for cancel/stale outcomes; the
  "Remember this key?" offer fires right after a passphrase unlock there.

### Changed

- **Tutorial walls dropped** (user feedback): the Home "How it works" and
  "Security" cards are gone — status chips and quick actions only. Guides
  live in README/CHANGELOG, not in the app. Helper text in the contact
  dialogs was cut to single lines.
- **Complete Material 3 color schemes + rounder shapes**: v0.3.0 set six
  color roles and let the rest (primaryContainer, tertiary, …) fall back to
  the baseline purple — the blue/purple clash that read as broken on
  device. Every role is now declared (calm indigo primary, slate secondary,
  warm ochre tertiary for the FAIR strength hint), cards 24dp / dialogs
  32dp rounded. Contact cards use proper containers; the 🔒/🔓 emoji labels
  became plain colored text.
- Contact dialogs migrated off hardcoded literals to string resources
  (localizable); profile pickers use radio buttons consistently.

### Compatibility

- No schema, permission or wire-format change — in-place update over
  0.4.0-alpha (versionCode 7). Still zero network code.

### Verified

- New `BiometricDiagnosticsTest` (symbolic gate mapping + fallback);
  everything else compile- and CI-gated as usual.

## [0.4.0-alpha] - 2026-11-06 — old-key-era decryption + passphrase advisor

The two items picked from the v0.4.0 roadmap proposal, audited in
`docs/DEEP-SCAN-2026-11-06.md`.

### Added

- **Old-key-era message decryption**: an envelope whose keyId belongs to a
  superseded era now decrypts. `KeySessionManager` keeps a bounded LRU
  historical cache (≤ 8 entries, decrypt-only — a historical key is never
  the sending key and never visible to active-session lookups); entries
  are wiped on eviction, expiry, `lock(contact)` and `lockAll`. With no
  historical session yet, decryption returns `NeedsEraUnlock`; the UI
  collects the passphrase of that era and `unlockEra` re-derives it
  against the era's wrapped digest verifier (the same honest-error
  contract as `unlockContact`). Biometric offers stay off for superseded
  eras — a fingerprint wrap is bound by AAD to the latest era only.
- **Stale-active-session guard**: a latest-era envelope is never decrypted
  with an active session that predates the rotation (previously a
  guaranteed AEAD failure); it now asks for a fresh unlock.
- **Passphrase advisor** (`security:policies`): a live strength meter
  under every passphrase field, plus a recommended-passphrase generator
  in the new-contact dialog (7 words from a 512-word list + 2 ASCII
  digits, `Locale.ROOT` — 69 bits guaranteed on top of the Argon2id KDF
  cost). The recommendation is display-only and never stored; it is
  copied through the `ClipboardAccess` chokepoint. Enforcement stays
  with `KeyPolicy`.

### Changed

- `unlockContact` and `unlockEra` share `verifyAndPersistVerifier` (the
  extracted verifier step; v1→v2 migration rewrite and best-effort
  first-unlock persist preserved).
- Era-aware passphrase prompts across the tools tab, the companion flows
  and the dormant IME; `MessageCipher` gained `NeedsEraUnlock` /
  `unlockEra` and lost the unreachable `StaleKeyEra` outcome.

### Compatibility

- No Room schema change (multi-era rows already existed), no new
  permissions, no DI changes — installs as an in-place update over
  0.3.0-alpha. Still zero network code.

### Verified

- New unit suites: historical-session lifecycle, era-unlock round trip,
  stale-session guard, advisor (wordlist pinned at 512 so it cannot
  silently drift). CI green (`gradle test` + `assembleDebug`, JDK 21 /
  SDK 36) after the audit's two fixes (F1 initialization order `c4de3ce`,
  F2 floor-case test expectation `4efe856`); full report in
  `docs/DEEP-SCAN-2026-11-06.md`.

## [0.3.0-alpha] - 2026-11-05 — the lost release, re-implemented

> The original v0.3.0-alpha source was never committed (see spec 005 and
> `recovered/v0.3.0-alpha-from-apk`). This release re-implements everything
> the shipped APK contained, in Kotlin, on top of v0.2.2-alpha.

### Added

- **Floating assistant** (opt-in, off by default): a draggable overlay
  bubble with quick Encrypt / Decrypt-clipboard actions, hosted by a
  specialUse foreground service. Needs the "Display over other apps"
  permission, which Settings deep-links to; "Close" on the panel persists
  the toggle off. Adds `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` permissions —
  with the feature off, none is requested and no service runs.
- **Fingerprint unlock** (opt-in, off by default): after a passphrase
  unlock the user may remember the session key behind the system
  BiometricPrompt. The key is wrapped with an AES-256-GCM cipher whose key
  lives in the Android Keystore with `setUserAuthenticationRequired(true)`;
  the wrap AAD is `"contactId:eraId"`, so a rotated key era invalidates the
  remembered wrap automatically (stale wraps are discarded, never used for
  the wrong era). Turning the feature off wipes every blob AND the Keystore
  wrap key. The passphrase path always remains available.
- **In-app Encrypt / Decrypt tabs**: manual encryption and decryption of
  typed or pasted text (contact picker, CK1 detection, unlock cards, result
  block with Copy) — for apps where neither the selection toolbar nor the
  sharesheet reaches the text. The main navigation is now five tabs:
  Home, Encrypt, Decrypt, Contacts, Settings.
- **Settings expansion**: timeout picker with 1 min → 24 h choices
  (`KeyPolicy.MAX_SESSION_TIMEOUT_MILLIS` extended accordingly,
  `nearestTimeoutChoice()` added), fingerprint-unlock toggle,
  floating-assistant toggle, and the existing KDF-strength picker.
- **Typed notices**: companion notices are now `(string resource, arg)`
  pairs (`UiNotice`) resolved at render time — locale-correct after
  configuration changes and assertable in unit tests.
- **`MessageCipher` biometric surface**: `currentEraId()`,
  `exportSessionKey()` (returns a copy) and `unlockContactWithKey()` (the
  caller's buffer is wiped on every path).

### Changed

- `BaseCompanionActivity` and `MainActivity` extend `FragmentActivity`
  (required by the system BiometricPrompt; still a `ComponentActivity`).
- The "Replace in field" affordance now also appears for
  decrypt-from-selection (insertable for both directions).
- Home screen rebuilt around quick actions, status chips, how-it-works and
  the security-properties card, all string-resource driven.

### Security

- No new network code; the app remains local-first with zero network
  access. Both new features are opt-in and permission-gated; every crypto
  path still funnels through the audited `MessageCipher` facade.
- The assistant service holds no key material and performs no crypto — it
  only launches the existing companion activities.

### Verified

- 90 unit tests, 0 failures (logic; device gate for the new UI remains, as
  before, a maintainer task — see spec 005 task list).
- `assembleDebug` and `assembleRelease` (unsigned) build cleanly.

## [0.2.2-alpha] - 2026-11-05 — remediation of the 2026-11-05 deep-scan findings

Every code finding from `docs/DEEP-SCAN-2026-11-05.md` is addressed below
(numbering follows that document). Machine-verified after the fixes:
197 unique tests / 19 classes / 0 failures (+10 regression tests) and a
clean `assembleDebug`.

### Fixed — shipped code (MEDIUM findings)

- **Unlock-verifier race (finding 1)**: `KeySessionManager.activate()` now
  copies `keyBytes` (it already copied `keyId`/`salt`). A concurrent
  `lock()`/`lockAll()` between activation and verifier persistence can no
  longer zero the buffer mid-wrap and permanently poison the era's verifier.
- **Keystore failures misreported as "Wrong passphrase" (finding 2)**: a
  `KeyProtectionException` during unwrap (e.g. master key invalidated by a
  system update) is now surfaced as `UnlockOutcome.Failed` with a
  "Keystore error" reason; the cached Keystore key handle is evicted so the
  next attempt reloads it. Wrong-passphrase detection is unchanged (it comes
  from the verifier comparison, not from the unwrap).
- **No `FLAG_SECURE` on plaintext screens (finding 3)**: all companion
  activities (the only screens that render decrypted plaintext) now set
  `FLAG_SECURE` — screenshots and screen recording capture nothing.
- **Decrypt failures headed "Encrypt" (finding 4)**: each entry activity
  declares its mode and passes it into notices; the clipboard flow sets its
  mode before the first read. An empty/oversized **decrypt** invocation is
  now correctly titled "Decrypt".
- **Stored verifier was the full session key (finding 5)**: the at-rest
  verifier is now a Keystore-wrapped **digest** (`0x02 || SHA-256(key)`).
  Wrong-passphrase rejection is identical, but a DB + Keystore compromise no
  longer yields the raw key. Legacy full-key blobs from ≤ 0.2.1 are still
  accepted and transparently migrated on the next successful unlock.

### Fixed — shipped code (LOW/INFO findings)

- Contact-creation failures now render inside the add-dialog, not only
  behind its scrim (finding 6).
- Settings copy no longer references the removed keyboard toolbar; the About
  card shows the real `BuildConfig.VERSION_NAME` instead of a stale "0.1.0"
  (finding 7).
- Expired sessions are swept by a low-frequency background sweeper, so an
  expired-but-unqueried key no longer sits in memory until process death
  (finding 8).
- Key fingerprints format with `Locale.ROOT` — Persian-locale devices no
  longer render "۰۷۲"-style digits that break out-of-band comparison
  (finding 9).
- Companion notices no longer render twice (card + inline error line,
  finding 10).
- Removed the dead `onNewIntent` "singleTop relaunch" path no manifest
  activity declares (finding 11).
- All companion flows run through a never-crash launcher: unexpected
  exceptions surface as notices with `busy` cleared, and the passphrase
  `CharArray` is wiped on every path including exceptions (finding 12).
- The timeout slider writes to DataStore only when the drag finishes, not
  on every animation frame (finding 13).
- "Copy invite" goes through the `ClipboardAccess` chokepoint and reports
  success/failure inline (finding 14).
- `deleteContact` clears the fingerprint cache along with the era cache
  (finding 15).
- Same-millisecond key eras resolve deterministically (`ORDER BY createdAt
  DESC, sessionId DESC`); `importEra` loses its check-then-act window on the
  unique `keyId` index by re-classifying after a constraint failure
  (finding 16).
- Copied decrypted plaintext is marked `EXTRA_IS_SENSITIVE` on Android 13+
  so the clipboard overlay hides it (finding 17).

### Fixed — dormant `:keyboard` library (compiled + tested, not shipped)

- `replaceAll` is now atomic (`beginBatchEdit`/`endBatchEdit`) with a
  best-effort restore of the deleted draft if the insert fails, and replaces
  an active selection directly instead of leaving it in place (findings 21/22).
- `readDraft` includes the active selection and returns an honest
  `DraftRead` result: `Unreadable` and `TooLong` are no longer conflated
  with an empty draft (finding 22).
- Draft reads are capped at the 16 KiB protocol limit (+1 to detect
  overflow) instead of hauling up to 100,000 chars per side over Binder on
  the main dispatcher (finding 23).
- Locking the active contact also clears the decrypted preview
  (finding 24).
- The `isSensitiveField` live re-check is now consulted at action time
  (encrypt, decrypt-clipboard, insert) as defense in depth (finding 26).

### Documentation

- README rewritten for the shipped v0.2 companion architecture and the
  197-test reality (it still described the v0.1 IME and 140 tests).
- THREAT_MODEL / ARCHITECTURE aligned with the digest verifier, the
  clipboard-copy behavior, and the actual wipe triggers.

### Not addressed (documented decisions)

- Finding 25 (`KeyboardLifecycleOwner` dispatches only CREATE/DESTROY):
  safe today and only reachable if the IME ships again; left as documented
  limitation.
- Finding 27 (GitHub PAT) — re-provisioned by the maintainer after the scan.
- Finding 28 (release signing keystore lost): the next *signed* release
  still needs a new keystore and a disclosed key change; no signing identity
  is recoverable on this machine.

## [0.2.1-alpha] - 2026-11-04 (device-testing pre-release, tag `v0.2.1-alpha`)

Samsung companion fixes (spec 004), from the maintainer's Galaxy A54 5G
(One UI) report against v0.2.0-alpha. Same signing key as v0.2.0 —
installs as an in-place update, no uninstall needed.

### Fixed

- **False "the clipboard is empty" on Android 10+** (spec 004 RC-1):
  clipboard launches read the clipboard during `onCreate`, before the
  window had focus — an unfocused window is denied clipboard access
  since Android 10. Clipboard workflows now defer the read to
  `onWindowFocusChanged(hasFocus=true)`, with an idempotence guard, a
  transient "Reading the clipboard…" state, and a **Try again** button
  on clipboard notices.
- **Clipboard failure taxonomy** (spec 004 FR-2): genuinely empty,
  non-text (e.g. an image), and OEM-blocked (`SecurityException`)
  outcomes now produce distinct, honest notices instead of all
  collapsing into "empty".
- **`EXTRA_PROCESS_TEXT_READONLY` ignored on encrypt** (spec 004 RC-3):
  read-only selections no longer offer "Replace in field", which the
  calling app would have ignored.

### Investigated, no code change

- Selection-toolbar entries not visible on One UI (spec 004 RC-2): the
  shipped manifest was verified correct (`aapt2 dump xmltree`); on
  modern Android/One UI third-party `PROCESS_TEXT` handlers live in the
  toolbar's ⋮ overflow, and some apps draw custom selection UIs that
  never surface them. The clipboard ladder is the designed fallback;
  see `specs/004-samsung-companion-fixes/DEVICE_TEST.md` for exactly
  where to look.

### Tests

- 187 unique tests / 18 classes / 0 failures (+27 over v0.2.0's 160):
  19 `ClipboardParser` tests (Persian, emoji, multiline LF/CRLF,
  non-text mimes, blocked-vs-empty) and 8 companion workflow round-trip
  tests over the real crypto stack.

## [0.2.0-alpha] - 2026-11-04 (device-testing pre-release, tag `v0.2.0-alpha`)

Companion architecture (Option E, spec 003): CipherKey becomes a
permission-free companion app instead of a keyboard. You keep Samsung
Keyboard — or any keyboard — and encryption moves to OS text mechanisms.
No backend, no accounts, no network code, zero Android system permissions.

### Added

- "Encrypt (CipherKey)" selection-toolbar entry (`ACTION_PROCESS_TEXT`):
  select a draft in any app, confirm, and the selection is replaced in
  place by the `CK1:` envelope. Sticky last-used contact; passphrase
  prompt only when the session is locked.
- "Decrypt (CipherKey)" selection-toolbar entry: select a received
  `CK1:` message (read-only views supported), preview the plaintext
  explicitly, copy it — or insert it directly in editable fields.
- "Decrypt with CipherKey" sharesheet target (`ACTION_SEND text/plain`)
  for messengers where sharing is easier than selecting.
- Clipboard fallback ladder on the Home screen ("Encrypt clipboard" /
  "Decrypt clipboard") for apps whose selection toolbar is unavailable.
- Hardening seam (`ProcessTextContract`) with adversarial unit tests:
  null/blank/oversized/malformed inputs produce graceful notices, never
  crashes or silent truncation.
- Sticky-contact preference (single additive DataStore key).

### Changed

- The `:keyboard` IME module is no longer part of the release APK. The
  shipped app registers no input method at all, which removes the entire
  Compose-in-IME crash class observed on device with v0.1.0. The module
  remains in-repo and keeps compiling in CI.
- Home screen reworked to the companion workflow (IME setup card removed
  with the module).

### Security

- Still zero Android permissions. Clipboard is read only on explicit
  user action in the foreground through the single `ClipboardAccess`
  chokepoint. Decrypted plaintext is previewed and never written to disk.

### Known issues

- Selection-toolbar entries depend on the target app supporting standard
  text processing; per-app variance is real (fallback ladder exists).
- Release builds are signed with a NEW v0.2 key: the v0.1.0 keystore was
  lost with a build-environment recycle. Uninstall v0.1.0 before
  installing v0.2.0 (v0.1.0 never launched on device anyway).

## [0.1.0] - 2026-11-04

First alpha. Local-first end-to-end encryption layered on top of any Android
messaging app via the keyboard (IME). No backend, no accounts, no network
code, zero Android system permissions.

### Added

- 15-module Gradle project: `app` (management UI), `keyboard` (the IME —
  contains no cryptographic code), `core` (crypto, messages, keys,
  sessions), `data` (Room, repositories, preferences), `security`
  (Keystore protector, clipboard chokepoint, Argon2id policy profiles),
  `plugins` (public-key plugin API + registry).
- Mode A encryption: Argon2id key derivation (RFC 9106 known-answer
  vector verified byte-identical) + AES-256-GCM AEAD; the entire CK1
  protocol header is bound as associated data.
- `CK1:` versioned wire envelope (strict canonical base64url, golden
  vector pinned in tests) and `CKS1:` invite codes for contact pairing.
- Compose-based keyboard with QWERTY + symbols layouts, crypto toolbar,
  passphrase unlock dialog, draft replacement via InputConnection (with
  clipboard fallback), explicit clipboard decryption with preview-before-
  insert, and sensitive-field (password) suppression.
- Contact and key-era management backed by Room; DataStore settings;
  Android Keystore wrapping for at-rest key material; session manager
  with idle timeout, cross-contact isolation, and wipe-on-lock.
- 140 unique unit tests across 15 classes (0 failures), including
  two-device roundtrip simulation, wrong-passphrase rejection (verifier
  and AEAD paths), ciphertext/header tamper detection, Persian/Unicode
  roundtrips, and zero-clipboard-read checks on password fields.
- Documentation: `docs/PROTOCOL.md`, `docs/ARCHITECTURE.md`,
  `docs/THREAT_MODEL.md`, `docs/PLUGINS.md`, engineering report.
- GitHub Spec Kit integration: project Constitution (security-first,
  honesty non-negotiables) and specifications under `specs/`.
- CI: unit tests, debug build, Gradle wrapper validation, and secret
  scanning on every push and pull request.

### Security

- Zero Android permissions (only the AndroidX self-owned
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` marker); `allowBackup=false`;
  no INTERNET permission; no Accessibility service; clipboard is read
  only immediately after an explicit user action.

### Known limitations (v0.1)

- No on-device verification has been performed yet (no emulator/device in
  the build environment) — treat as alpha software.
- No forward secrecy in mode A (static shared passphrase per key era).
- Messages encrypted under superseded key eras cannot be decrypted.
- Latin QWERTY layout only (the crypto stack is fully Persian-capable).
- Metadata (sizes, timing, participants) remains visible to the transport.
- Release builds ship unminified for this alpha (documented rationale in
  the release notes).
