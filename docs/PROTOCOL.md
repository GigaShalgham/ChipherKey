# The Turnypt Wire Protocol (version 1)

The wire protocol of **Turnypt** (formerly CipherKey). v1.1.0 moved the
visible prefixes off the retired brand — envelopes write `TNP1:` and
invites `TNS1:`; the CipherKey-era `CK1:`/`CKS1:` (and the binary CKF1
magic, HKDF info and MIME type) still decode unchanged. The payload layout
behind every prefix is byte-for-byte the same.

Status: **implemented and frozen for v1** — any breaking change requires a
new version byte and a migration note here.

## Overview

A Turnypt message is a self-contained, authenticated envelope:

```
"TNP1:" + base64url(payload, no padding)    ← written by Turnypt (v1.1.0+)
"CK1:"  + the same payload                   ← CipherKey-era, still decoded
```

Both prefixes decode identically: detection is by prefix, automatic, and
never surfaced to the user. The payload layout — including the keyId that
resolves the contact and its friend era — is unchanged between the two,
so a legacy envelope walks the exact same path.

The decoder is strict: it rejects a missing prefix, `=` padding, the
standard base64 alphabet (`+`, `/`), embedded whitespace, payloads above
256 KiB and any envelope whose encoded form exceeds 360,000 characters —
all before any cryptographic work.

### Cipher negotiation (v0.6.0)

The cipher byte carries `0x01` (AES-256-GCM) or `0x02`
(ChaCha20-Poly1305, RFC 8439). Both use a 96-bit nonce and a 128-bit tag,
so the header layout below is identical for either — the field is inside
the AAD, and the receiver picks its implementation from the wire, never
from local settings. Adding `0x02` is a **forward-compatible extension**:
old builds reject it with `UnsupportedAlgorithm(0x02)`, exactly the
receiver policy below, so no version bump is required. ChaCha20-Poly1305
runs through the BouncyCastle lightweight API (no JCA provider
insertion), giving one implementation on every API level including the
JVM tests. The user picks a preference (Automatic / AES / ChaCha) in
Settings; Automatic micro-benchmarks both once per process and uses the
faster — a sending-side choice only.

## Payload layout (passphrase mode, mode id 0x01)

All multi-byte integers are big-endian. Offices are absolute byte offsets.

```
offset  size  field                         notes
0       1     protocol version              0x01
1       1     encryption mode               0x01 = PASSPHRASE; 0x02 = PUBLIC_KEY (reserved)
2       1     cipher algorithm id           0x01 = AES-256-GCM; 0x02 = ChaCha20-Poly1305 (v0.6.0)
3       1     KDF algorithm id              0x01 = Argon2id (v0x13, RFC 9106)
4       16    session key identifier        public; selects the local key era
20      7     Argon2id parameters           exponent(1) ‖ iterations(4) ‖ parallelism(2)
27      16    Argon2id salt                 public (also inside every invite)
43      12    AEAD nonce                    96-bit, fresh random per message
55      …     ciphertext ‖ tag              AES-256-GCM, 128-bit tag
```

Minimum payload length: 55 + 16 = **71 bytes** (empty plaintext).

### Argon2id parameter encoding

Memory is a power-of-two exponent in KiB (so 64 MiB ⇒ exponent 16),
restricted to `3 ≤ exponent ≤ 22`, `1 ≤ iterations ≤ 65,536`,
`1 ≤ parallelism ≤ 64`, and `2^exponent ≥ 8 × parallelism` (RFC 9106
minimum). Receivers MUST reject out-of-range parameters — the receiver
re-derives with exactly the parameters in the envelope, and because the
parameter bytes are inside the AAD, a tampered value fails authentication
rather than silently changing the key.

## Cryptographic construction

```
K     = Argon2id(passphrase, salt, params)        # 256-bit, contact-era scoped
nonce = random(96 bits)                           # fresh per message
AAD   = payload[0 .. 55)                          # the entire header above
C‖T   = AES-256-GCM(K, nonce, plaintext, AAD)     # tag length 128 bits
```

Design notes:

- **Every header byte is authenticated.** Flipping any bit of version, mode,
  algorithm ids, keyId, KDF parameters, salt or nonce causes authentication
  failure.
- **Nonce uniqueness.** A fresh 96-bit random nonce per message under a
  contact-era key; the collision budget for 2^32 messages is ≈ 2^-33 per
  pair (birthday bound), far beyond the 16 KiB × human-scale usage this
  protocol targets. Key eras additionally bound the exposure of any single
  key.
- **Receiver algorithm policy.** Version, mode, cipher and KDF ids are
  matched against the compiled-in set; unknown values are rejected with a
  distinct error before authentication.

## Limits

| Limit | Value |
|---|---|
| Max plaintext | 16 KiB |
| Max payload (envelope bytes) | 256 KiB |
| Max encoded chars | 360,000 |

## TNS1 invites (key-era sharing)

```
"TNS1:" + base64url(payload, no padding)    ← written by Turnypt (v1.1.0+)
"CKS1:" + the same payload                   ← CipherKey-era, still decoded

offset  size  field
0       1     invite version                0x01
1       1     KDF algorithm id              0x01 = Argon2id
2       7     Argon2id parameters
9       16    session key identifier
25      16    Argon2id salt
```

Total payload: **41 bytes** → 55 base64url characters + prefix. An invite
carries **no secret** (salt and keyId are public — the salt is inside every
envelope anyway). The shared passphrase is exchanged out of band, ideally
in person.

## Test vectors

### RFC 9106 Argon2id (KDF cross-check)

Inputs: `m=32 KiB, t=3, p=4, pwd=0x01×32, salt=0x02×16, secret=0x03×8,
ad=0x04×12, tag=32 bytes`.

```
tag = 0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659
```

The shipped BouncyCastle-based derivator reproduces this byte-for-byte
(`Argon2KeyDerivatorTest`); the expected value was cross-checked against
the RustCrypto password-hashes known-answer suite (`argon2/tests/kat.rs`,
`argon2id_v0x13`), which itself validates against the reference C
implementation.

### Golden vector (envelope cross-check)

Fixed inputs:

```
key        = (i*11+5) mod 256, i = 0..31
keyId      = (i+100) mod 256,  i = 0..15
salt       = (i+200) mod 256,  i = 0..15
params     = Argon2id, 64 MiB (exponent 16) / 3 passes / 1 lane
nonce      = 0,1,…,11            (deterministic random source)
plaintext  = "CipherKey CK1 golden vector v1"   (UTF-8; historical fixture —
            it is INSIDE the ciphertext, so changing it would change the
            envelope bytes; the anchor stays stable by design)
```

Expected envelope (asserted in `Ck1CodecTest`; the same body under the
legacy `CK1:` prefix is asserted to decode identically):

```
TNP1:AQEBAWRlZmdoaWprbG1ub3BxcnMQAAAAAwAByMnKy8zNzs_Q0dLT1NXW1wABAgMEBQYHCAkKC8sUsW5uL0eG8bhe2kE3XlxcDpO9PEBc00dLZZzZnYIjOjfyDpIIFAuNE5dTp0c
```

Any conforming implementation must produce the identical string
(AES-GCM is deterministic given key/nonce/plaintext/AAD). The body is
unchanged from the pre-rebrand vector — only the written prefix moved.

## Error taxonomy (decoder side)

| Condition | Error |
|---|---|
| Missing `TNP1:`/`CK1:` prefix / bad base64url / wrong length | `MalformedMessage` |
| Payload below 71 bytes | `MalformedMessage` |
| Unknown version byte | `UnsupportedVersion(v)` |
| Unknown mode / cipher / KDF id | `UnsupportedMode/Algorithm/Kdf(id)` |
| Envelope size above limits | `MessageTooLarge` |
| AEAD tag mismatch (incl. any header tamper) | `AuthenticationFailed` |

## CKF1 — the file container (v0.6.0)

Images (and any large file) travel as a **CKF1** container (extension
`.cki`, MIME `application/x-cipherkey`) instead of a base64 envelope —
same key era, same Argon2id passphrase, same contact. Full design:
`docs/IMAGE-CRYPTO.md`. Layout (all integers big-endian):

```
offset size field
0      4    magic "CKF1"
4      55   the exact CK1 v1 header above (cipher byte included)
59     …    sealed chunks in order, each = AEAD(chunk ‖ 16-byte tag)
```

- Chunks are 64 KiB of plaintext, sealed independently: peak memory is
  one chunk, so file size can never OOM the process (the v0.5.1 lesson).
- `fileKey = HKDF-SHA256(eraKey, salt = base nonce, info =
  "CipherKey-CKF1-v1")` — every file gets its own effective key, so the
  4-byte nonce prefix + 8-byte chunk counter below cannot collide across
  files under one era key (the Tink streaming-AEAD argument).
- Chunk i nonce = `baseNonce[0..4) ‖ i (8 B BE)`; chunk i AAD =
  `prefix(59) ‖ i (8 B BE) ‖ lastFlag(1)` — header, position and
  finality are all authenticated: reordering, splicing and truncation
  fail.
- The final chunk is strictly shorter than 64 KiB; exact-multiple inputs
  get a zero-length final chunk as the truncation sentinel. A stream
  ending without one is `FileTruncated`.
- No `CKF1` magic at the start is `NotACipherFile`; an unknown header
  keyId is the same `UnknownKey` as the text path.

> v1.1.0 migration note: the binary CKF1 magic, the
> `"CipherKey-CKF1-v1"` HKDF info string, the `.cki` extension and the
> `application/x-cipherkey` MIME type are DELIBERATELY UNCHANGED. They are
> invisible bytes inside every .cki file ever written; renaming them buys
> no visible identity and would churn the file ecosystem. Only the
> user-visible text prefixes moved (TNP1:/TNS1:, legacy readable).
