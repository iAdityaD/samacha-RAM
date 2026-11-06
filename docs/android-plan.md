# Standalone Android app — plan

Status: approved direction, implementation starting with Phase 1. Supersedes the "Android client of the Python host" model in `release-v0.2.md` once Phase 3 ships. The Python host remains an optional desktop companion and an import source.

## Goals

1. The phone does everything itself: feeds, ranking, briefing, library, evidence workspace. No computer or self-hosted server is required.
2. The user chooses storage: **phone only** or **Google Drive** (sign in with Google). Modes can be switched without data loss.
3. Minimal storage, data, and battery use.
4. A Kotlin Multiplatform-ready core so an iOS client (with iCloud) can reuse the logic later.

## Technology decision

**Kotlin (native Android) with a Kotlin Multiplatform `core` module.**

React Native was rejected for this app: the work is dominated by background refresh, storage, encryption, sync, and on-device inference (ONNX Runtime, LiteRT-LM, Keystore, Block Store, WorkManager), all of which would need native Kotlin bridges anyway. It would also add the JavaScript runtime to app size and memory. The existing Android client is already Kotlin/Compose.

`core` is written in `commonMain` with no Android or JVM APIs, except small platform pieces (compression) behind `expect`/`actual`. Today it has a `jvm` target, consumed by the Android app; iOS targets can be added later without moving code.

## Storage decision

**One structured, compressed SQLite database.** Markdown is an export format only.

| Option | Space | Speed | Sync | Verdict |
|---|---|---|---|---|
| Markdown files per record | Each small file occupies at least one 4 KiB filesystem block | Needs a separate index | Hundreds of files | Rejected |
| JSON documents in SQLite (current host) | Field names repeated per record | Full scans | Whole database | Rejected |
| Structured SQLite + compression + contentless FTS5 | Smallest | Fastest | Small encrypted change batches | **Chosen** |

### Space techniques

| Technique | Detail |
|---|---|
| Normalized tables | Sources, topics, and authors stored once; integer keys |
| Text compression | Text ≥ 512 bytes stored as a 1-byte codec tag + DEFLATE; shorter text stored plain (compression overhead would exceed savings) |
| Contentless FTS5 index | `content=''`, `contentless_delete=1`, `detail=column`: the index stores no second copy of the text and no token positions |
| Feed retention | Unsaved items: full excerpt 30 days → excerpt dropped → row deleted at 90 days. A 64-bit URL fingerprint is retained so old items are not re-added as new. Saved, annotated, or cited items are never pruned |
| Dedup | Canonical URL + SimHash title fingerprint for near-duplicate syndication |
| Content-addressed PDFs | One file per SHA-256; optional text-only mode keeps compressed text and drops the binary |
| Quantized vectors | int8 per dimension + one float scale (≈4× smaller than float32); saved/recent items only |
| History rollup (optional) | Events older than 6 months collapse to monthly counts |
| Incremental vacuum | `auto_vacuum=INCREMENTAL`, reclaimed in background maintenance |
| No images | Feed images are not downloaded |
| Storage screen | Per-category usage, limits, and cleanup actions |

The size estimate is measured by `core` tests on synthetic data, not assumed: see Phase 1 results below.

The Android app bundles its own SQLite (`androidx.sqlite:sqlite-bundled`) because FTS5 and `contentless_delete` are not guaranteed in the platform SQLite on every Android version. Cost: about 1–2 MB of APK size.

## Storage modes

| Mode | Sign-in | Behavior |
|---|---|---|
| Phone only | None | Database in app-private storage. Optional encrypted backup file to any user-chosen location (Storage Access Framework) |
| Google Drive | Sign in with Google | Same local database, plus encrypted sync to the Drive **appDataFolder** (hidden; the app cannot see other Drive files) |

The local database is always the working copy, so both modes are fast and work offline.

## Google Drive sync

- Each device uploads compressed, encrypted **change batches** (typically a few KiB). Batches are periodically compacted into a snapshot; older batches are deleted.
- Restore = latest snapshot + later batches. Feed items are not synced (they are re-fetched); AI models never sync; PDFs are optional.
- Synced: interests, sources, saved items, notes, highlights, claims, reviews, relations, missions, watchlists; history only when enabled.
- Merge: per-record, per-field versions using a hybrid logical clock. Sets (saves, tags) use add/remove operations. Concurrent note edits use a three-way line merge; when lines conflict, both versions are kept for the user to resolve. No silent loss.
- Encryption: a random data key encrypts everything (AES-GCM) before upload. The data key is protected by Google Block Store with screen-lock end-to-end encryption where the device supports it, and wrapped by a user recovery key/passphrase (Argon2id) for loss of all devices.
- Uploads are debounced; large transfers can be restricted to Wi-Fi.

## Feed engine on the phone

- Conditional requests (ETag / Last-Modified) and compressed responses.
- Per-source exponential backoff and health status.
- Redirects followed with per-hop validation; private-network destinations blocked.
- Refresh ~30 minutes before the briefing time, catch-up on app open, skip on low battery, optional Wi-Fi only.

## Module layout

```
android/
├── core/   Kotlin Multiplatform (jvm target now): models, URL canonicalization,
│           ranking, briefing, dedup, retention, vector quantization, schema,
│           repository over a small SQL driver interface, Markdown export,
│           import from the Python host export
├── app/    Android UI, onboarding (storage mode), storage screen, notifications
└── (later) sync/ and ai/ modules split out of app when they grow
```

## Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| 1. Core + database | `core` module: schema, compression, contentless FTS, retention, dedup, ranking/briefing, quantization, Markdown export, host-export import | Tests pass on JVM with a real SQLite; measured size for 10,000 items recorded |
| 2. Feeds on phone | Bundled SQLite driver on Android, feed fetch/parse, conditional requests, backoff, WorkManager refresh | Refresh works with no computer |
| 3. Reading loop | Feed, layers, missions, saves, notes, briefing, notifications, storage screen | Full daily use offline |
| 4. Google sign-in + Drive | Credential Manager sign-in, Drive appDataFolder, change batches, snapshots, Block Store key, restore, mode switching | Two devices sync without loss; restore tested |
| 5. Evidence workspace | Claims, reviews, relations, stories, documents/PDF text, comparisons | Same exact-quote rules as the host |
| 6. Polish | Accessibility, performance, release signing | Physical-device checklist passes |

## User setup (needed before Phase 4)

1. Google Cloud project (free): enable the Drive API, configure the OAuth consent screen, create an Android OAuth client with the package name `com.samacharam.app` and the SHA-1 fingerprints of the debug and release signing keys.
2. A release signing key, stored safely.
3. Optional: Google Play developer account.

## Phase 1 results

`android/core` is a Kotlin Multiplatform module (JVM target) with no Android dependencies. Run its tests with `gradle -p android :core:jvmTest`; the Android workflow runs them on every push and uploads `storage-report.txt`.

Implemented: models, URL canonicalization (trailing slash preserved), SimHash/Jaccard near-duplicate detection, ranking/briefing (port of `web/core.js`, with the 15-item queue reporting minutes for exactly the returned items), int8 vectors, tagged DEFLATE text codec, ISO date handling, schema v1 with migrations scaffold, `Library` repository (upsert/dedup/tombstones, contentless FTS search, saves, highlights, history with tracking pause, retention, compaction, stats), Markdown export, and import of the Python host's `/api/export`.

36 tests pass on JVM against a real SQLite (SQLite JDBC, which bundles FTS5 like `androidx.sqlite:sqlite-bundled`).

Measured storage, one year of heavy use (10,000 items over 365 days, 500 saved with notes and two highlights, 20,000 reading events; synthetic Zipf-distributed text, seed 42):

| Measurement | Size |
|---|---|
| Raw title + excerpt text | 13.84 MB |
| Python host format (JSON in SQLite), articles only | 26.46 MB |
| New schema, all 10,000 items kept, plus saves and history | 14.74 MB |
| **New schema after retention (steady state)** | **5.21 MB** |
| Same, after an occasional full `VACUUM` | 3.60 MB |

Synthetic text compresses less than real prose, so real-world sizes should be similar or smaller.

Defects found by these tests and fixed before commit:
- Single-digit tokens were dropped from titles, so titles differing only by a number ("GPT 4" / "GPT 5") were treated as duplicates.
- `PRAGMA incremental_vacuum` frees pages only as it is stepped; some drivers step it once. Without the bounded loop in `Library.compact()`, retention left the file larger (16.6 MB) instead of smaller.
- Retention trimmed excerpts of items it was about to delete.

## Model connections

No model is built in or required. Ranking, dedup, search, watchlists, and retention are rules. Each AI task (classification, summarization, embeddings, reranking, evidence drafts, Q&A) has its own route: a primary and an optional fallback, chosen from:

| Connection | Protocol | Where text goes |
|---|---|---|
| Built-in rules (default) | — | Nowhere. Classification, extractive summary, matching-passage Q&A. Embeddings, reranking, and evidence fail rather than fake output |
| On-device | LiteRT-LM (`.litertlm`), ONNX BERT | Stays on the phone |
| Local network | Ollama, OpenAI-compatible (llama.cpp, LM Studio) | A computer on the owner's network |
| Cloud | OpenAI-compatible, Anthropic Messages API, Google Gemini | The provider, with the owner's key |

Location is derived from the endpoint, never asserted: private and loopback literals, `.local`/`.lan`/`.home.arpa`, single-label names, and 100.64.0.0/10 (Tailscale) are local network; everything else is cloud. On Android, `OkHttpTransport` resolves each host once, checks every address against the expected location, and connects only to those addresses (no DNS rebinding). It follows no redirects and caps responses at 8 MB.

Rules enforced in `core/ai`, before any key is read or request built:
- Cloud endpoints need HTTPS and explicit per-provider consent to receive source text.
- Local-only mode blocks all cloud providers. A cloud fallback needs a second, separate opt-in.
- A daily cloud request limit (default 100) is counted before sending and persisted in the library.
- Keys live only in the Android Keystore vault; they are not in settings JSON, sync, exports, or error messages (provider errors are redacted).
- Replies must be JSON of the task's shape. Cited outputs must quote passages that exist in the source (whitespace-insensitive); classification labels must be the owner's own topics. Anything else fails and the fallback runs.
- Opt-in assisted ranking: a reranking model may reorder briefing items only within their interest layer, never surfaces muted items, and failures keep the rule-based order.

Anthropic adapter: raw HTTP to `/v1/messages` with `anthropic-version: 2026-10-21` (this version rejects `temperature`, so none is sent), `output_config.effort` low for classification/reranking and medium otherwise, only `text` content blocks are read, and `refusal`/`max_tokens` stop reasons are errors. For Claude models that support it, server-side refusal fallback is enabled (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). Anthropic has no embeddings endpoint. The adapter is hand-written HTTP rather than the Java SDK because `core` is platform-neutral (for a later iOS client) and provider-neutral.

The settings screens (add model, test connection, assign routes, privacy switches) arrive with Phase 3, when the Android app has its own library database.

## Remaining limits

| Limit | Handling |
|---|---|
| Losing every device and the recovery key loses the encrypted backup | Block Store key backup + one-time recovery key prompt |
| Android may delay background work | Early refresh, catch-up on open, optional exact-time notification |
| Drive sync needs internet | Offline edits queue and sync later |
| Bundled SQLite adds ~1–2 MB APK | One-time; consistent FTS5 on every device |
| AI models are large | Optional, never synced, limits and cleanup |
| Android UI cannot be exercised in this development environment | Pure-Kotlin core tested on JVM; Android build/unit tests in GitHub Actions; physical-device checklist |
