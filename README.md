# Smart Home 3D — Backend

Independent API for projects, original files, backups, and Home Assistant sessions. This repository builds/deploys on its own, without Angular, npm, or frontend directories.

For the entire application, see **docs/INSTALLATION.md** in the frontend repository: download, Home Assistant, interface, and first connection. Run this page's commands from the backend repository root.

Includes [electricity/gas collection](docs/ENERGY.md): tariffs/profiles in the project, hourly aggregates/monthly actuals in the DB, a monthly dashboard API, and ZIP v2 backups (restoring v1/v2). Collection remains disabled until enabled for a home and requires a valid owner session to unlock HA; sign in after restarting.

## Local startup

Requires Java 21 or later. The `mvnw` launcher uses installed Maven or downloads Maven 3.9.11 from the official repository with SHA-512 verification.

Download using Git or **Code → Download ZIP**, enter the directory containing `pom.xml`, and check `java -version`. After ZIP extraction, run `chmod u+x mvnw` if the launcher is not executable. Initial dependency downloads require Internet access.

```bash
./mvnw verify
SH3D_HA_URL=http://127.0.0.1:8123 ./mvnw spring-boot:run
```

The backend listens at `http://127.0.0.1:8090`; check `http://127.0.0.1:8090/health`. Leave the terminal open. HA is optional: omit `SH3D_HA_URL` for storage only, or replace it with your instance's URL. This does not start or change HA, containers, or system services.

In the frontend at `http://127.0.0.1:4200`, select English and open **Settings → Storage and server**. Enter `http://127.0.0.1:8090`, choose **Check server**, and create the owner with a password of at least 12 characters. There are no predefined credentials. Use the same hostname for frontend/API. The server account is separate from HA.

This version supports one owner's home storage shared across devices. Family accounts and separate permissions are not included.

## Data and backups

The default database is **on-disk H2**, at `data/smarthome.mv.db`, relative to the startup directory. Projects/files survive process restarts. Use a stable directory or volume when deploying the JAR.

```bash
./mvnw package
SH3D_DATABASE_URL='jdbc:h2:file:/private/path/smarthome;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH' \
  java -jar target/smart-home-3d-backend-0.1.0.jar
```

Replace `/private/path/smarthome` with your chosen database path and add `SH3D_HA_URL` if using HA. Preserve the same variables on every start. This runs the JAR in the terminal; it does not install a system service.

The frontend's **Copy local projects to the server** imports geometry, bindings, previews, original PDFs/images, and GLBs in one transaction per project. Local originals remain available. Every copy/restore receives new project/asset IDs, with references updated. Missing referenced files reject the operation without leaving a partial copy.

**Download full backup** produces a ZIP of the active project containing `project.json`, `manifest.json`, `energy.json` (energy history), and `assets/`. **Restore ZIP backup** creates a new atomic copy and accepts older v1 ZIPs. Accounts, password hashes, cookies, sessions, and tokens are excluded. For an administrative backup of the entire database, stop the process before copying the H2 directory, or use the chosen database's tools.

Writes use revisions and conditional requests: stale writes fail with HTTP 412. There is no automatic merge or real-time collaboration. Export your local version and use **Reload server version** in the frontend.

Initial limits: JSON 35 MB, assets 50 MB, expanded bundles 150 MB, 2,000 imported files. ZIP backups are generated in memory; size RAM accordingly. Assets are immutable: a new version uses a new ID. Deleting a project cascades to its files.

## Sessions

The account stores only a username and PBKDF2 password hash. The browser receives an HttpOnly/SameSite=Lax session cookie, with up to seven days' lifetime and inactivity expiration. Writes require CSRF; HTTP/WebSocket origins are explicit.

HA sign-in uses OAuth with PKCE S256 and belongs to the **owner profile**. The DB stores only an **AES-256-GCM** encrypted refresh token; access tokens/PKCE verifiers stay in RAM. At server sign-in, the password derives the key using PBKDF2-HMAC-SHA256 (600,000 iterations, dedicated random 32-byte salt). Passwords, keys, and plaintext tokens are not persisted or sent to the browser. No external key file is required.

**After restarting, sign in as the server owner** to reopen the last home and HA connection without importing backups or repeating HA authorization. Keep the same database and `SH3D_HA_URL`. The browser resumes an existing valid session; backend restarts require sign-in because HTTP sessions are not serialized.

**Sign out of the server** and expiration close that browser's WebSockets and clear its RAM key, retaining the owner's connection. **Unlink Home Assistant from your profile** removes the encrypted credential, closes all owner bridges, and attempts remote revocation. If HA is offline, local removal still occurs; revoke from HA's profile if needed. Temporary network renewal failures preserve the connection; refresh rejection with 400/401/403 requires new authorization without deleting homes/bindings. A different HA URL does not receive the previous instance's token.

Existing profiles initialize at first sign-in after upgrading. The earlier RAM-only token cannot be recovered after shutdown: **connect HA once** with this version to save authorization in the profile. New tables are added without rewriting existing projects.

A database copy contains salts, password hash, and encrypted refresh token, allowing offline password guessing. Use a strong password. The encryption key is not derived from the stored hash. Password change/reset is not yet implemented; future changes must re-encrypt the credential or require HA authorization again. Losing the password prevents unlocking the connection; projects and home backups remain separate. Home ZIP/JSON excludes profile, salt, key, and credentials; administrative full-DB backups include the encrypted credential.

Details/sources: [profile and persistent credentials](docs/PROFILE_SECURITY.md). The bridge preserves cookie/origin authentication, command allowlisting, and sensitive-field redaction; UI/renderer do not handle HA credentials.

## Configuration

| Variable | Default / meaning |
| --- | --- |
| `SH3D_BIND` | `127.0.0.1` |
| `SH3D_PORT` | `8090` |
| `SH3D_ORIGINS` | Comma-separated localhost/127.0.0.1 origins on frontend ports 4200/4300 |
| `SH3D_HA_URL` | Empty disables HA; fixed server URL without credentials or parameters |
| `SH3D_COOKIE_SECURE` | `false` for localhost development; use `true` for production HTTPS |
| `SH3D_DATABASE_URL` | File-based H2; alternatively a PostgreSQL JDBC URL |
| `SH3D_DATABASE_USER` / `SH3D_DATABASE_PASSWORD` | Database credentials from the environment |

PostgreSQL driver/schema are included. Example: `SH3D_DATABASE_URL=jdbc:postgresql://db:5432/smarthome`, with environment-provided credentials. Automated tests use H2 in PostgreSQL mode; verify a real PostgreSQL server in your installation. Automatic H2-to-PostgreSQL data migration is not provided.

For LAN devices, use HTTPS and specific origins. Frontend/API must share a site, such as one hostname on different ports or frontend and `/api/` behind one reverse proxy. During development, use the same hostname (`localhost` or `127.0.0.1`) for both. `compose.example.yaml` is a local example; no containers start automatically. In Docker, HA must be reachable from the container.

## Repository and deployment

This repository has its own `pom.xml`, Maven launcher, tests, `Dockerfile`, `.gitignore`, and [API v1 contract](docs/API.md). It can be moved/cloned anywhere. The frontend knows only its runtime API URL and JSON schema v1; it is not a Maven dependency.

For Docker Engine/Compose deployment, stop any native backend on port 8090 and prepare a private environment file:

```bash
if [ ! -e .env ]; then cp .env.example .env; fi
```

Edit `.env`. Leave `SH3D_HA_URL` empty for storage only; otherwise choose an HA URL reachable by **both the container and the browser authorizing HA**, normally HA's LAN address. Container `localhost` means the container. The included `host.docker.internal` alias reaches the host, but normally does not resolve in the OAuth browser.

```bash
docker compose --env-file .env -f compose.example.yaml config --quiet
docker compose --env-file .env -f compose.example.yaml up -d --build api
docker compose --env-file .env -f compose.example.yaml logs --tail 50 api
```

The API listens at `127.0.0.1:8090`; named volume `home-data` preserves `/app/data`. Example origins include frontend ports 4200, 4300, and 8080; for HTTPS, set the actual origin and `SH3D_COOKIE_SECURE=true`. `config --quiet` validates syntax without starting containers.

The Docker volume is **separate from native `data/`** and does not automatically contain earlier homes. Transfer homes/assets using ZIP backups and recreate owner/HA authorization, or plan an administrative DB migration with services stopped. Keep directory/Compose project name stable to reuse the volume.

Before updating source, back up, then repeat `up -d --build api` with the same environment file. Stop with `docker compose --env-file .env -f compose.example.yaml stop api`; resume with `up -d api`. Do not delete the data volume. Docker must start with the system for `restart: unless-stopped` to resume the service.

Complete Docker builds on new machines need target-environment testing; Compose syntax checks do not build images.

Sources: [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [servlet CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html), [Home Assistant authentication](https://contributors.home-assistant.io/core/internals/auth-api/).

## Support this project

Smart Home 3D is still under active development. New features and improvements will be added over time.

If you find this project useful, consider supporting its development with a donation. Thank you for your support!

[Donate via PayPal](https://www.paypal.me/flackwind)
