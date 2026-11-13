# Image Encryption — Native Integration Design (v0.6.0-alpha)

> Planning document. Plain Markdown by project rule (Spec Kit is permanently
> retired). This feature was **not** bolted on: it reuses the CK1 key era
> system, the contact vault, the Argon2id passphrase infrastructure, the
> session manager and the unlock UX exactly as they are. No parallel key
> store, no second contacts list, no separate crypto stack.

## 1. Problem statement (user feedback, 2026-11-13)

Three integration gaps were reported against a WIP demo that never shipped:

1. **Ecosystem isolation** — the image feature must use the SAME contacts,
   passphrase and key-era infrastructure; no parallel systems.
2. **Information architecture** — image encryption needs a real place in
   Quick Access and inside the Encrypt / Decrypt sections, not a side door.
3. **AES vs ChaCha** — the two algorithms must be explained (technical
   difference, speed/resource trade-off, when each is the right choice,
   hardware acceleration impact), and an **Automatic** option should pick
   per device without sacrificing security or compatibility.

Success criterion: the user must not feel an external tool was glued onto
CipherKey; image encryption must feel like it was always a core capability.

## 2. What exists and is reused verbatim

| Existing asset | How image encryption uses it |
|---|---|
| `ContactRepository` + Room vault | The image flow picks the SAME contact |
| `SessionKeyRepository` (key eras, CKS1 invites) | Same era lookup by keyId; same `awaitingFirstPassphrase`, `markUsed` |
| `KeySessionManager` | ONE unlocked session per contact serves text AND images |
| Argon2id derivation + `UnlockVerifier` | Passphrase unlock identical; fingerprint wraps shared |
| `ProtocolHeader` CK1 55-byte layout | Reused byte-for-byte inside the CKF1 file prefix |
| `CipherAlgorithm` wire ids | Extended with id `0x02` (additive; old builds reject it by design) |
| UI language (SectionCard, ContactPicker, PassphraseInput, notices) | Same components, same unlock card, same biometric offer |
| Companion architecture (FLAG_SECURE, noHistory sheets) | New `ImageCipherActivity` follows the same pattern |

## 3. Wire format — CKF1 (CipherKey File, version 1)

Binary container for large payloads (images today, any file later). A
`.cki` file is a drop-in attachment for any messenger.

```
offset size field
0      4    magic "CKF1"
4      55   CK1-compatible header (same layout as text CK1):
            version 0x01 | mode 0x01 | cipher id (0x01 AES / 0x02 ChaCha)
            | kdf 0x01 | keyId(16) | Argon2 params(7) | salt(16) | base nonce(12)
59     ...  sealed chunks, in order
```

- **Chunking**: plaintext is split into 64 KiB chunks. Each chunk is sealed
  independently with the AEAD (`ciphertext ‖ 16-byte tag`). Peak memory is
  one chunk (~64 KiB + buffers) — a 256 MB input can never OOM the heap
  (the v0.5.1 lesson, applied by construction).
- **Per-file subkey**: `fileKey = HKDF-SHA256(ikm = session key, salt =
  base nonce, info = "CipherKey-CKF1-v1")`. Every file gets its own
  effective key, so the 4-byte nonce prefix + 8-byte chunk counter below
  can never collide across files (same argument as Tink streaming AEAD).
- **Chunk nonce**: `baseNonce[0..4) ‖ counter (8 bytes BE)` — unique per
  chunk by construction.
- **Chunk AAD**: file prefix (59 bytes) `‖ counter (8 BE) ‖ last-flag (1)`.
  Binds every chunk to this file's header, position and finality:
  reordering, splicing and truncation all fail authentication.
- **Final-chunk rule**: the last chunk is strictly SHORTER than 64 KiB; a
  file whose size is an exact multiple gets a zero-length final chunk as
  the truncation sentinel. A stream that ends without a short chunk is
  rejected as `FileTruncated`. Empty input ⇒ single empty final chunk.
- **Algorithm**: the cipher id byte selects AES-256-GCM or ChaCha20-
  Poly1305 per chunk — recorded in the file, so any receiver with any
  preference decrypts it. Security is identical (256-bit key, AEAD,
  128-bit tag).
- Suggested file name: `<original>.cki`, MIME `application/x-cipherkey`.

Text messages stay on the frozen `CK1:` wire format — untouched.

## 4. Cipher algorithm choice (AES-256-GCM vs ChaCha20-Poly1305)

Both are AEAD constructions with a 256-bit key, 96-bit nonce and 128-bit
tag; both are recorded per message/file and decrypt transparently.

| | AES-256-GCM | ChaCha20-Poly1305 |
|---|---|---|
| Standard | NIST SP 800-38D; TLS 1.3 default | RFC 8439; TLS, WireGuard, SSH |
| Structure | block cipher (SPN) + GHASH | ARX stream cipher + Poly1305 |
| Fast when | hardware AES (ARMv8 crypto unit, x86 AES-NI) — multi-GB/s | always fast in software — no tables, constant-time by construction |
| Weak when | no hardware unit: constant-time software AES is slow | — (consistent everywhere) |
| Timing side channels | software tables must be avoided; hardware is constant-time | naturally constant-time in software |
| Typical Android | 2015+ flagships (ARMv8): clear winner | older ARMv7 devices: ~1.5–3x faster than software AES |

**Automatic mode**: at first use, CipherKey micro-benchmarks both
implementations on THIS device (256 KiB seal each, cached per process) and
uses the faster one. The measurement reflects real conditions — hardware
units, the JIT, the bundled ChaCha implementation — not a guess from a
CPU-name table. Preference lives in Settings → Security (`Automatic`
default); the UI shows what Automatic currently resolved to.

## 5. Information architecture

```
Home (Quick Access)
├── Encrypt text / Decrypt text            (existing)
├── Encrypt clipboard / Decrypt clipboard  (existing)
└── Encrypt image / Decrypt image          (NEW — 3rd tile row, in-app routes)

Encrypt tab  ──[ Text | Image ]──▶  Image: pick image (SAF) → same ContactPicker
                                    → same unlock card (passphrase + fingerprint)
                                    → cipher chip (Automatic/AES/ChaCha) → Encrypt
                                    → CreateDocument "name.cki" → saved + share
Decrypt tab  ──[ Text | Image ]──▶  Image: pick .cki (SAF) → contact auto-resolved
                                    from keyId → unlock if needed → decrypted to
                                    private cache → preview (downsampled) →
                                    Save image (CreateDocument) / Share

Sharesheet / file managers                (external entry points, same engine)
├── SEND image/*           → ImageCipherActivity → encrypt flow
├── SEND x-cipherkey       → ImageCipherActivity → decrypt flow
└── VIEW content x-cipherkey → same decrypt flow

Floating assistant panel
└── "Encrypt image" row    → ImageCipherActivity picker mode
```

Decrypt streams to a private cache file, decodes a downsampled preview,
and never holds the plaintext image in memory twice. Nothing hits disk
except the cache preview, which is deleted with the flow.

## 6. Module map (what changed)

- `core/crypto`: `CipherAlgorithm.CHACHA20_POLY1305` (id 0x02);
  `ChaCha20Poly1305AeadCipher` (BouncyCastle lightweight API — already a
  dependency for Argon2id, zero new deps); `AeadCiphers` registry;
  `Hkdf`; `Ckf1` + `CkfChunkedCipher`; algorithm-aware `ProtocolHeader`
  and `PassphraseCryptoProvider`; `AutomaticCipherSelector`; new errors.
- `data/preferences`: `CipherPreference` (AUTO / AES / CHACHA) setting.
- `data/repository`: `MediaCipher` facade — the image twin of
  `MessageCipher` (same eras, same sessions, same outcomes:
  NeedsUnlock / NeedsEraUnlock / UnknownKey / Failed); `MessageCipher`
  gains an injectable algorithm source.
- `app`: ToolsViewModel image flows (one engine shared by tabs and the
  companion activity); segmented Encrypt/Decrypt tabs; Home tiles;
  `ImageCipherActivity`; assistant row; Settings cipher dialog with the
  per-option explanations; EN + fa strings.

## 7. Compatibility & security notes

- Old CipherKey builds that receive a `0x02` message/file reject it with
  `UnsupportedAlgorithm` — the designed forward-compatible behavior; no
  protocol version bump needed (documented in PROTOCOL.md).
- Text `CK1:` envelopes: wire-compatible, unchanged.
- CKF1 files carry the same header AAD guarantees; tampering with ANY
  header byte, chunk byte or the finality marker fails authentication.
- No new permissions: SAF pickers (OpenDocument/CreateDocument) and
  content URIs need none. Still zero INTERNET permission.
