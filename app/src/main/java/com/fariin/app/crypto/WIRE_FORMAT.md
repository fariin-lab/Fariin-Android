# Fariin end-to-end encryption: wire formats

The formats the iOS app writes and reads, which this package reproduces byte for byte.
References are `file:line` in `C:\Users\abdik\Documents\Kulan-iOS` (read 2026-11-02).

## Primitives

- libsodium through swift-sodium (`Crypto.swift:4-14`). Android: lazysodium (same libsodium C code).
- `box` = `crypto_box_easy` (X25519 + XSalsa20-Poly1305). Output = MAC (16 bytes) followed by the ciphertext. Nonce 24 bytes, random, stored separately.
- `secretbox` = `crypto_secretbox_easy` (XSalsa20-Poly1305), same layout, 24-byte nonce, 32-byte key.
- Keys: 32-byte X25519 public and secret keys (`sodium.box.keyPair()`, `Crypto.swift:266`).
- base64 everywhere: Foundation `base64EncodedString()`, the STANDARD alphabet (`+` `/`), padded with `=`, no line breaks (`Crypto.swift:12-14`). The only exception is `rk` (below), read with `looseBase64` (`Crypto.swift:1042-1046`).
- Text is UTF-8 (`Bytes(text.utf8)`). Bytes that do not decode as UTF-8 count as a failed open (`String(bytes:encoding:.utf8)` returns nil).

## Identity key and its publication

| What | Where | Format |
|---|---|---|
| Private key | iOS Keychain, service `com.fariin.messenger.crypto`, account `fariin_secret_key_v1.<uid>`, `AfterFirstUnlockThisDeviceOnly` (`Crypto.swift:79, 1133, 1179`) | b64 of 32 bytes |
| Public key copy | same, account `fariin_public_key_v1.<uid>` (`Crypto.swift:80`) | b64 of 32 bytes |
| Published key | Firestore `users/{uid}.publicKey` (`Crypto.swift:315-337`) | b64 of 32 bytes |
| Key history | `users/{uid}.publicKeyHistory`, `arrayUnion([old, new])` on every change (`Crypto.swift:334-336`) | array of b64 |

Android keeps the same item names. The private key is encrypted with AES-256-GCM under an Android Keystore key that cannot leave the device (`KeystoreSecretStore`), which matches "this device only". A key is published only after both halves were stored (`Crypto.swift:272-286`). "Could not read the store" is never treated as "no key" (`Crypto.swift:233-241`).

Peer keys are cached with a ring of earlier keys per person, newest first, 8 at most (`Crypto.swift:100-118, 158-169`). Every open tries the current key and then the ring (`Crypto.swift:121-132`).

## Chat ids

- 1:1: the two uids sorted, joined with `_` (`CallService.swift:406`). The other party is the half that is not me (`Crypto.swift:661-666`).
- Group: any id with no `_` (`Crypto.swift:1036`).

## 1:1 text: `enc1:`

```
enc1:<b64 nonce 24 bytes>:<b64 box(utf8 text, nonce, recipientPub, senderSecret)>
```

`Crypto.swift:672-688`. Parsed by splitting on `:` with empty parts kept; it must give exactly 3 parts (`Crypto.swift:729-732`). The sender reads their own copy with the recipient's public key, because the DH secret is symmetric.

Results of the iOS `decrypt` (`Crypto.swift:716-744`), which the UI relies on:

| Input | Result |
|---|---|
| starts with `enc:` (old, fake format) | `[old message]` |
| does not start with `enc1:` | the string as it is (plain text) |
| my key or the peer key not loaded | `…` (U+2026) |
| wrong key, tampered, bad base64, not UTF-8 | the lock emoji U+1F512 |

Used for: `messages/*.text`, `conversations/*.lastMessage`, `replyTo.text`, image captions, `blurhash`, `thumb` (ChatService.swift:1427-1454, 1955-2012).

## Group text: `encg1:` (groups that are still end-to-end encrypted)

```
encg1:<b64 of UTF-8 JSON>
JSON = { "n": b64 data nonce, "c": b64 secretbox(text, n, msgKey),
         "w": { "<uid>": "<b64 box(msgKey, wrapNonce, memberPub, authorSecret)>.<b64 wrapNonce>" } }
```

`Crypto.swift:920-965`. `msgKey` is 32 random bytes. Every member with a published key gets a wrap, the author included; members without a key are skipped; at least one member other than me must get a wrap, otherwise the send fails with "missing recipient key" (`Crypto.swift:944-957`). A member opens their wrap with the AUTHOR's public key (`authorId` of the message) (`Crypto.swift:983-1016`).

iOS writes the JSON with `JSONSerialization`: key order is not fixed and `/` comes out as `\/`. Android writes `n`, `c`, `w` in that order with plain `/`; both are valid JSON for either reader.

A group whose document says `e2ee == false` sends text in the clear (`Crypto.swift:1111-1114`, `ChatService.swift:1500`).

## Files: `EncMeta`

The file bytes in Storage are `secretbox(file, n, fileKey)` (MAC + ciphertext, no nonce). The envelope is stored on the message as `enc` (and `imageEnc`, `lastImageEnc` on the conversation) (`Crypto.swift:40-60`, `ChatService.swift:484, 1236, 2010, 2033`):

| Field | 1:1 | Group | Plain group file |
|---|---|---|---|
| `v` | 1 | 1 | 0 |
| `n` | b64 data nonce | b64 data nonce | "" |
| `k` | b64 box(fileKey, kn, recipientPub, senderSecret) | "" | "" |
| `kn` | b64 wrap nonce | "" | "" |
| `w` | absent | `{uid: "wrapB64.nonceB64"}` as in `encg1` | absent |
| `a` | absent | author uid | absent |

1:1: `Crypto.swift:807-832`, opened only when `v == 1` (`Crypto.swift:899`). Group: `Crypto.swift:837-861, 870-893`. A `v: 0` file is returned as it is in a group and refused in a 1:1 (`Crypto.swift:868`). Firestore hands `v` back as a number (Long on Android).

## Released group keys: `rk`

A message (or conversation) may carry `rk: { "<b64 data nonce>": "<b64 32-byte item key>" }`, filed by the server after a member released the key of an old sealed group item. Keys and values are read with `looseBase64` (standard or URL-safe, padding optional). Used only for group ids and only when my own wrap is missing or does not open (`Crypto.swift:1024-1101`).

## Push payload

The push data carries `cid`, `aid` (sender uid), `sn`, `g` ("1" = group), `kind`, `ackUid`, and `ct`: the same `enc1:` or `encg1:` string as the message text, or `tx` (plain text, plain groups only). The notification extension opens `ct` with the receiver's secret key and the sender's current key, then the ring (`NotificationService.swift:99-132, 201-258`). No other format exists for pushes.

## Safety number

`SafetyNumber.swift:13-69`. Per person: `h = 0x00 0x00 || pub || utf8(uid)`, then 5200 times `h = SHA-512(h || pub)`. Take the first 30 bytes as six 5-byte big-endian numbers, each `mod 100000`, zero-padded to 5 digits: 30 digits. The chat's number is both 30-digit strings, the smaller one (string compare) first: 60 digits, shown as 12 groups of 5. QR payload: `fariinverify:v1:` + the 60 digits.

## What is proven and what is assumed

Proven by the unit tests (`app/src/test/java/com/fariin/app/crypto/`):
- X25519 public keys match RFC 7748; `crypto_box_beforenm` matches NaCl's published vector; `box_easy` equals `secretbox_easy` under the shared key (MAC first).
- `enc1:` and `encg1:` strings for fixed keys and nonces equal strings built independently by a Python script (PyNaCl) following the Swift code line by line. The safety number equals the same script's SHA-512 rebuild.
- Group envelopes written the Foundation way (`\/` escapes, other key order, spaces) open.

Assumed, not checked on an iPhone:
- `Data(base64Encoded:)` rejects unpadded input. Android is strict the same way. iOS always writes padded base64, so this only matters for hand-made input.
- The safety number has no iOS-produced vector; it was checked against an independent rebuild of the Swift code, not against an iPhone screen.
- The "phone replaced by a newer sign-in publishes nothing" check (`Crypto.swift:320-331`) needs the device registry, which is not ported; `FirestoreKeyDirectory(mayPublish = ...)` is the hook.
