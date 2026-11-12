# Smart Home 3D API v1

[OpenAPI 3.1 specification](openapi.json).

Base `/api/v1`. UTF-8 JSON; `SH3D_SESSION` cookie, HttpOnly/SameSite=Lax, `Cache-Control: no-store`. HA tokens are not part of the browser contract. All APIs except session, initial setup, and login require an authenticated owner. All writes, including setup/login/logout, require `X-SH3D-CSRF` obtained from GET session. Credentialed CORS permits configured origins only.

| Method and path | Contract |
| --- | --- |
| `GET /session` | `{authenticated, setupRequired, username, csrfToken, homeAssistant:{configured,url,connected,message}}`; CSRF stays in client memory; `connected` means unlockable owner authorization, not network reachability |
| `POST /auth/setup` | `{username,password}`; allowed once; creates a session and PBKDF2 hash |
| `POST /auth/login` | `{username,password}`; rotates the session and unlocks profile HA credentials; reread `/session` after the response |
| `POST /auth/logout` | Invalidates cookie/session and closes its bridges; retains the owner's HA connection |
| `GET /profile` | `{activeProject:string}`; last selected home ID or empty if absent/deleted; owner only |
| `PUT /profile/active-project` | Existing `{projectId:string}`; remembers selection in profile; CSRF required |
| `GET /projects` | Array `{project: HomeProjectV1, revision: number}` |
| `GET /projects/{id}` | HomeProject v1 document, `ETag: "n"` header |
| `PUT /projects/{id}` | v1 document; create with `If-None-Match: *`, update with `If-Match: "n"`; new revision in ETag |
| `DELETE /projects/{id}` | Requires `If-Match`; cascades to assets; 204 |
| `GET /projects/{id}/assets` | Array `{id,projectId,name,mediaType,size}` |
| `PUT /projects/{id}/assets/{assetId}` | Multipart `file`; immutable/idempotent for identical content/metadata; 204 |
| `GET /assets/{assetId}` | Original bytes, Content-Type, X-Project-Id, percent-encoded X-Asset-Name; attachment |
| `POST /imports` | Multipart: `project` v1 JSON; `assets` JSON of `{id,name,mediaType}`; `files` parts with filename equal to ID; new atomic copy returning `{project,revision}` |
| `GET /projects/{id}/backup` | ZIP: document, format-2 manifest, energy.json, binary assets; no access credentials |
| `POST /backups/restore` | Multipart ZIP `file`; new atomic copy returning `{project,revision}` |
| `GET /projects/{id}/energy?year=2026` | Twelve months of quantities/costs/coverage, device details, actuals, collection status; [energy contract](ENERGY.md) |
| `POST /projects/{id}/energy/sample` | Requests a shared HA sample; minimum 10 seconds between attempts; read-only device access |
| `PUT /projects/{id}/energy/monthly` | `{month,utility,amount,cost}`; monthly actual replaces automatic total |
| `DELETE /projects/{id}/energy/monthly/{month}/{utility}` | Removes the actual without deleting samples |
| `POST /ha/authorize` | Configured `{origin}`; returns `{authorizeUrl,state}`; server-only PKCE verifier, 5-minute expiry |
| `POST /ha/authorize/complete` | `{code,state}` from same browser/session; single-use; saves encrypted refresh token in owner profile; empty response, no tokens |
| `POST /ha/authorize/cancel` | `{state}`; removes matching pending attempt |
| `POST /ha/disconnect` | Removes owner's HA connection, closes all owner bridges, attempts revocation |
| `WS /ha/ws` | Authenticated cookie, explicit origin, active HA session; HA handshake without client access_token |

`GET /health`, outside the API base, exposes `{status:"ok",apiVersion:1}` without creating accounts.

## Documents and revisions

HomeProject v1 includes `schemaVersion:1`, `id`, `name`, ISO dates, `building`, `bindings`, and `scene`. Coordinates are metric; walls use shared vertices; openings have offset/width/height/sill, alongside rooms, furniture, and asset references. The server validates structure, references, dimensions, openings, boundaries, and reserved fields; the frontend has its own model validator. HA entities are not stored in projects; bindings are. Building the model requires no dependency between repositories.

Optional schema-v1 `dashboard` contains temperature preferences only: `indoorTemperatureEntityId` (`sensor.*`/`climate.*`), `outdoorTemperatureEntityId` (`sensor.*`), `outdoorWeatherEntityId` (`weather.*`), and boolean `useWeatherFallback`. All four fields are required when the object exists; an empty ID means no source. Backend domain validation does not query HA; temporarily absent sources are allowed. CRUD, import, and ZIP backup/restore preserve settings; current readings/weather stay in live provider state. No DB migrations/new endpoints are required. Older homes without `dashboard` remain valid. Interface language is a public browser preference outside the project/server profile.

Project/asset IDs: `[a-zA-Z0-9_-]{1,200}`. Frontend UUIDs satisfy this contract. Other internal IDs are unique document labels. Revisions belong to HTTP and do not change exportable JSON schema.

Clients retain the revision actually opened. A later listing does not authorize overwriting an unseen revision. Stale writes return 412 without saving; missing preconditions return 428. Never retry a conflict by blindly replacing the ETag.

JSON errors `{message:string}`: 400 invalid request/project, 401 sign-in required, 403 CSRF/origin/access denied, 404 missing resource, 409 duplicate, 412 conflict, 413 too large, 428 missing precondition, 429 login rate limit, 502 HA unavailable. Messages exclude credentials and original requests.

## ZIP and import

Current manifest: `{formatVersion:2,assets:[{id,projectId,name,mediaType,size}]}`. Files use `assets/{id}`; the document is `project.json`; `energy.json` contains hourly aggregates/monthly actuals. Restore also accepts format 1 without energy.json. It validates entry count/size, paths, duplicates, declared files, and references without extracting filesystem paths. Errors roll back project/assets/energy in one transaction; existing projects are never overwritten. Optional JSON-v1 `energy` configuration does not contain time series.

## Home Assistant bridge

The server sends `auth_required`; the client replies `{type:"auth"}`. The bridge authenticates upstream using a RAM token and sends `auth_ok`. Clients then send HA requests with IDs: snapshots, services, read-only registries, `state_changed` events, ping, and only frontend-supported actions. Token creation, signed paths, HA configuration, and arbitrary services are not forwarded. Extra global targets in service data are rejected. App logout also closes existing WebSockets.

HA authorization persists in the profile, encrypted with a server-password-derived key. Logout/expiration do not revoke it. After restarting, owner sign-in restores authorization without another HA popup. Temporary errors preserve the connection; rejected refresh requires new authorization. See [profile and credentials](PROFILE_SECURITY.md).
