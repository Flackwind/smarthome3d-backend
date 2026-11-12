# Owner profile and restoration

Implemented at the user's request on November 5, 2026. The backend has one owner; all authenticated owner sessions share the last home and Home Assistant authorization.

## Persistent data

- `owner_account`: username and login-password PBKDF2 hash, as before.
- `owner_profile`: dedicated random encryption salt and last opened home ID.
- `owner_ha_connection`: HA URL, original OAuth client ID, encrypted refresh token.
- Projects/assets stay in their own tables; HomeProject schema v1 is unchanged.

New tables use `CREATE TABLE IF NOT EXISTS` without altering previous table data. The encryption salt is created only after valid sign-in, independently of the password-hash salt. An earlier RAM-only token is not migrated automatically: authorize HA once with this version.

## Encryption

After password verification, sign-in derives a 256-bit AES key using PBKDF2-HMAC-SHA256, 600,000 iterations, and a random 32-byte salt. The DB contains neither the password nor the derived key. The key belongs to a non-serializable HTTP-session object; its buffer is cleared on logout/expiration. Servlet sessions are not persistent. Explicit temporary arrays are cleared; Java/HTTP libraries still use immutable strings during login/token exchange, so immediate removal of every process-memory copy is not guaranteed.

`AES/GCM/NoPadding` comes from JDK JCA, without extra cryptographic dependencies. Every encryption uses a fresh random 12-byte nonce and 128-bit authentication tag. The versioned `v1:` envelope contains nonce and ciphertext/tag in Base64. Additional authenticated data binds ciphertext to owner, HA URL, and client ID. Wrong passwords, replaced metadata, or tampering fail decryption without deleting the connection.

Only the backend decrypts the refresh token; short-lived access tokens stay in RAM cache. HA-rotated refresh tokens are re-encrypted. The original client ID is reused when signing in from another browser. A different configured HA URL cannot reuse the previous instance's refresh token.

This limits exposure from a database copy, but does not protect a backend compromised while the user is signed in. Stored salts/hashes permit offline password guessing, so use a strong password. Future password change/reset must re-encrypt credentials with the new key or require HA authorization again. This feature does not implement password changes.

## Restoration and disconnection

1. After restarting, use the same database and `SH3D_HA_URL`.
2. Sign in as server owner to unlock the encrypted credential.
3. The frontend reads the profile, reopens the last home, and connects the HA bridge without another popup.
4. States/services/registries are reread from HA; home, bindings, and calibration already belong to the project. Previous position readings and monitoring opt-in remain temporary tab data.

Server logout closes only that session's WebSockets, retaining owner authorization without revoking other browsers. Explicit HA disconnection removes the owner credential, closes all bridges, and attempts revocation at HA. Network/5xx errors preserve the connection for retry; refresh rejection with 400/401/403 removes it and requires new authorization. Projects/assets are not deleted.

Unreadable ciphertext remains in the database for diagnosis/recovery. Explicit owner reauthorization can replace it; if the old credential cannot be decrypted, it cannot be remotely revoked, so complete revocation from the Home Assistant profile.

`/session` reports connection availability and a non-sensitive message. `connected` means an unlockable authorization exists for the configured instance; the bridge/UI verify actual reachability. Browser APIs expose no tokens, ciphertext, or salts. Home JSON/ZIP excludes profiles/credentials; an administrative full-DB backup includes ciphertext and requires the original password to unlock it.

## Primary sources

- [Home Assistant authentication: refresh tokens, client ID, revocation](https://contributors.home-assistant.io/core/internals/auth-api/).
- [OWASP: authenticated encryption and key separation](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html).
- [OWASP: PBKDF2-HMAC-SHA256 and derivation cost](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
- [Java 21 Cipher: AES-GCM, authentication, nonce uniqueness](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html).
