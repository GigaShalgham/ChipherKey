# Turnypt Architecture

## Module graph (acyclic by construction)

```
                    ┌──────────────────────────────────────────────┐
                    │                    :app                       │
                    │  management UI, ViewModels, Hilt aggregation │
                    └───────┬───────────────────────────┬──────────┘
                            │                           │
                     ┌──────▼──────┐             ┌──────▼──────┐
                     │  :keyboard  │             │ :data:...   │
                     │ IME (no     │             │ repository  │
                     │ crypto!)    │             │ database    │
                     └──────┬──────┘             │ preferences │
                            │                    └──────┬──────┘
                            │                           │
        ┌───────────────────▼───────────────────────────▼──────────┐
        │ :core:sessions   :core:messages   :core:keys   :core:... │
        │ (in-memory keys) (CK1/CKS1 codec)(protector)  crypto     │
        └───────────────────────────┬──────────────────────────────┘
                                    │
                     ┌──────────────▼──────────────┐
                     │ :security:storage (Keystore)│
                     │ :security:clipboard         │
                     │ :security:policies          │
                     └─────────────────────────────┘
```

Rules enforced by review (not yet by a dependency-analysis plugin — tracked
as future work):

- `:keyboard` has **no** crypto implementation and depends on
  `:data:repository` only through the `MessageCipher` facade.
- `:core:*` modules are pure Kotlin (no Android imports) and therefore unit
  testable on the JVM.
- `:data:repository` is the single composition point where storage,
  sessions, protection and the crypto provider meet.
- **v0.6.0 (images)**: `MediaCipher` is the file twin of `MessageCipher`
  in the same module — the SAME `ContactRepository`, `SessionKeyRepository`
  and `KeySessionManager` instances serve both, so one unlocked session
  encrypts text and files and there is no parallel key system anywhere.
  The CKF1 streaming work lives in `:core:crypto`
  (`Ckf1`, `CkfChunkedCipher`, `Hkdf`) and is pure JVM; the cipher for
  new encryptions (both facades) comes from one injected
  `CipherAlgorithmSource` that resolves the user's preference, including
  the Automatic mode's per-process benchmark (`AutomaticCipherSelector`).

## Runtime data flow

### Sending (keyboard)

```
[Encrypt] → ImeController.requestEncryptDraft()
          → TextFieldAccess.readDraft()            (InputConnection)
          → MessageCipher.encryptDraft(contact, draft)
              → SessionKeyRepository.latestEra     (Room)
              → KeySessionManager.activeSession    (memory; NeedsUnlock otherwise)
              → PassphraseCryptoProvider.encrypt   (AES-256-GCM, header as AAD)
              → MessageEncoder.encode              ("CK1:" + base64url)
          → TextFieldAccess.replaceAll(envelope)   (deleteSurrounding + commit,
                                                    fallback selectAll, fallback clipboard)
```

### Receiving (keyboard)

```
[Decrypt] → ImeController.requestDecryptClipboard()
          → ClipboardAccess.currentText()          (explicit user action ONLY)
          → MessageCipher.decryptEnvelope(text)
              → MessageDecoder.decode              (strict, zero crypto)
              → SessionKeyRepository.eraByKeyId    (contact auto-resolution)
              → era vs latest era check            (v0.4.0: latest → active
                                                  session; superseded →
                                                  historical session, else
                                                  NeedsEraUnlock → unlockEra)
              → KeySessionManager.activeSession / historicalSession
                                                   (NeedsUnlock / NeedsEraUnlock otherwise)
              → PassphraseCryptoProvider.decrypt   (AEAD auth gate)
          → DecryptedPreview (UI) → [Insert] → commitText
```

### Unlocking

```
passphrase → KeySessionManager.deriveKey   (Argon2id on background dispatcher)
           → verifier check (if wrapped blob exists: Keystore unwrap + compare)
              wrong → WrongPassphrase (derived key wiped, session NOT activated)
           → KeySessionManager.activate     (memory, fixed lifetime)
           → first unlock on device: persist Keystore-wrapped verifier

v0.4.0, superseded eras (unlockEra): same derivation and verifier path,
           → KeySessionManager.activateHistorical (bounded LRU cache,
              decrypt-only — never the sending key, never in the active map)
```

## Key lifecycle

```
creation:   ProtocolRandom.keyId()/salt()  ─► Room session_keys (public data)
unlock:     passphrase + salt ─Argon2id─► 256-bit key (memory only)
at rest:    verifier digest wrapped by AndroidKeystoreKeyProtector ─► Room (device-bound; never the raw key)
lock/expiry/replace/periodic-sweep:             Arrays.fill(0) on key material
```

## Threading model

- `DispatcherProvider` is injected everywhere; tests substitute
  deterministic dispatchers.
- Argon2id derivation runs on `Dispatchers.Default` via an injectable
  `deriveContext` (tests use `Unconfined` for inline execution).
- Room DAOs are suspend; DataStore flows are collected in ViewModels /
  the IME controller scope.
- The IME hosts Compose via `KeyboardLifecycleOwner` (Lifecycle +
  ViewModelStore + SavedStateRegistry owners), the same approach used by
  production Compose keyboards.

## Why these choices

- **Argon2id via BouncyCastle lightweight API**: no JCA provider insertion,
  so the Android platform providers (hardware-backed AES-GCM) stay intact.
  Verified byte-identical to the reference implementation on the RFC 9106
  Argon2id test vector (cross-checked against the RustCrypto KAT suite).
- **Contact-level key derivation** (not per message): one Argon2id run per
  unlock, then cheap AEAD per message; salt and parameters travel in the
  envelope so the receiver derives the same key without side channels.
- **Header-as-AAD**: every wire field (version, mode, algorithm, keyId, KDF
  parameters, salt, nonce) is authenticated — tampering anywhere fails.
- **Memory-only sessions + Keystore-wrapped verifier**: the passphrase never
  touches storage, cross-device portability is preserved (passphrase + salt
  are enough on a new device), and the wrapped blob adds device-bound wrong-
  passphrase detection after the first unlock.
- **Key eras**: rotation creates a new (keyId, salt, params) tuple; v0.1
  keeps old eras for audit but cannot decrypt their envelopes (documented
  limitation).
