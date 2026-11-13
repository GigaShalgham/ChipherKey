# Public-Key Mode (B): Plugin Evaluation

The CK1 wire format reserves **mode 0x02** for public-key envelopes. The
spec for this project required an *honest* evaluation of mature Signal
Protocol implementations, and — if no viable candidate exists — shipping the
integration interface only, with the gap clearly labelled. That is what
v0.1 does: `plugins/api` defines the contract, `plugins/registry` ships an
**empty** registry, and `BundledPlugins.MODE_B_AVAILABLE = false` is
surfaced in the UI.

## Candidates evaluated (as of the v0.1 session)

### 1. `org.signal:libsignal-android` (the current Signal library)

Facts (verified against Maven Central and the upstream repository):

- Version 0.86.5, packaging AAR with native libraries for armeabi-v7a,
  arm64-v8a, x86 and x86_64 (Rust core via JNI).
- **License: AGPL-3.0.** Turnypt is Apache-2.0; embedding AGPL code would
  force relicensing the entire application (or a separate closed-usage
  analysis we cannot honestly make here).
- Upstream states: *"Use outside of Signal is unsupported … All APIs and
  implementations are subject to change without notice."*
- The protocol assumes a **prekey distribution server** (or manual
  out-of-band PreKeyBundle exchange), requires implementing four persistent
  store interfaces (identity, prekeys, signed prekeys, sessions), identity
  verification UX, and per-device registration state.

Verdict: cryptographically excellent, **integration deferred** — the
license conflict alone is disqualifying for an Apache-2.0 distribution, and
the server-assumed architecture does not fit the local-first, no-backend
constraint of this milestone.

### 2. `signalapp/libsignal-protocol-java` (the legacy pure-Java library)

- GPL-3.0, **archived / unmaintained** upstream; no security fixes
  forthcoming.
Verdict: **rejected** (unmaintained + GPL).

### 3. Third-party ports (TypeScript/other reimplementations)

- Not maintained by Signal, varying licenses, no Android/Kotlin artifact of
  production quality.
Verdict: **rejected**.

## What ships instead

```kotlin
interface PublicKeyCipherPlugin {
    val id: String
    val displayName: String
    val protocolVersion: Int
    suspend fun generateKeyPair(): PluginKeyPair
    suspend fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray): PluginSealedMessage
    suspend fun decrypt(sealed: PluginSealedMessage, privateKey: ByteArray): ByteArray
}
```

plus an explicit, non-scanning `PluginRegistry`. Requirements for a future
plugin are documented on the interface (AAD-authenticated metadata, no
unauthenticated plaintext, Keystore-held private keys where possible).

## Re-evaluation criteria

A mode-B integration becomes viable when a candidate satisfies **all** of:

1. A license compatible with Apache-2.0 distribution (or a deliberate
   project decision to relicense).
2. A maintained, Android-consumable artifact (or a buildable Rust core we
   can wrap ourselves with multi-ABI builds wired into Gradle).
3. An offline-friendly session-establishment story (out-of-band prekey
   bundles) that does not require Turnypt to run a server.
4. A test plan extending the existing suite: known-answer vectors for the
   protocol, tamper tests, and device-migration scenarios.

Until then the honest status remains: **mode B is specified, not
implemented.**
