# Security Policy

## Reporting a vulnerability

Turnypt is an independent open-source project. Please report suspected
vulnerabilities privately by opening a security advisory on the project
repository (GitHub: *Security → Advisories*) or contacting the maintainers
directly. Please do not open public issues for suspected vulnerabilities.

We aim to respond within 7 days. Please include reproduction steps, affected
versions, and your assessment of impact.

## Scope and design commitments

- **No network code, no system permissions.** The manifest requests no
  Android system permissions (the only entry is the AndroidX-generated
  self-owned `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` marker); there is no
  HTTP stack anywhere in the dependency graph. Any PR adding either requires
  explicit justification.
- **No sensitive data in logs.** Field content, passphrases, key material
  and decrypted text are never logged — including in debug builds.
- **Clipboard access only on explicit action.** The single read chokepoint is
  `security/clipboard/ClipboardAccess.kt`, invoked only from the keyboard's
  Decrypt button; no listeners, no background reads.
- **Password fields are off limits.** `SensitiveFieldPolicy` disables all
  encryption UI on password/credential fields.
- **Keys are wiped.** In-memory session keys are zeroed on lock, replacement,
  expiry and service teardown. Wiping is best-effort on managed runtimes
  (see threat model for the residual-GC caveat).

## Cryptographic boundary

The cryptographic core (`core/crypto`, `core/messages`, `core/keys`,
`core/sessions`) is pure Kotlin and covered by unit tests including the
RFC 9106 Argon2id known-answer vector and a pinned CK1 golden vector. Changes
to `ProtocolHeader`, `Ck1`, `InviteCodec` or `AesGcmAeadCipher` MUST NOT
alter wire behaviour without a protocol version bump.

## Known limitations (v0.1)

Honest list — see `docs/THREAT_MODEL.md` for the full analysis:

- No forward secrecy in mode A (shared passphrase, no ratchet).
- Sender-side wrong passphrase on a *fresh* device is only detected at
  decryption time (documented; the Keystore verifier closes this after the
  first successful unlock on each device).
- Envelopes from superseded key eras cannot be decrypted (v0.1).
- Metadata (message length, timing, contact names) is exposed to the
  underlying transport exactly like an unencrypted message would be.
