# Smart Home 3D backend

- Read README.md and PROJECT_CONTEXT.md before making changes.
- Java 21 / Spring Boot project with independent Maven tooling. No source imports or builds from the frontend repository.
- Versioned HTTP contract at /api/v1; document incompatibilities in docs/API.md.
- Store projects/assets in the database. Encrypt the owner's HA refresh token with AES-256-GCM, using a key derived from the server password through PBKDF2 with an independent salt; keep access tokens and PKCE verifiers in RAM.
- No plaintext tokens in JDBC, files, logs, backups, or browser responses. Never persist passwords or encryption keys; store login passwords only as PBKDF2 hashes. Logout/expiration close the session without removing the owner's connection; explicit HA disconnection removes it and attempts revocation.
- Preserve CSRF, origin checks, HTTP/WebSocket authentication, and conditional revisions.
- Test saving/reopening, conflicts, backup/restore, and authorization cases with ./mvnw verify.
- Do not automatically start containers, modify Home Assistant, or install host services.
