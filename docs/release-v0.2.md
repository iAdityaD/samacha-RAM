# v0.2 — Native Android and evidence workspace

This guide supersedes the initial README/coverage descriptions where a deferred feature is now implemented. The original requirements remain the target, not a claim that every production-quality criterion is satisfied.

## Added in this iteration

| Area | Implemented behavior | Practical boundary |
|---|---|---|
| Native Android | Kotlin/Compose app, host login, feed/library views, search, interest editing, saved notes, share-to-save URL, provider registration, route assignment | Full evidence operations currently live in the companion web workspace; device UX still needs physical-device testing |
| Device inference | ONNX Runtime for supported uncased BERT classification/embeddings; LiteRT-LM for compatible `.litertlm` summarization/QA/review drafts | Not arbitrary ONNX/GGUF formats; model licenses, RAM, task fit, and real weights require validation |
| Secure device state | Android Keystore AES-GCM for host cookies and optional remembered login; private cache, backup disabled | A rooted/compromised device is outside this trust boundary |
| Background briefing | Opt-in WorkManager daily network task and notification permission, unique notification ID | Android may delay execution; a running reachable host and valid/remembered authentication are needed |
| Full documents | Rights-confirmed public HTML/PDF fetch jobs; PDF binary storage; authenticated download; text reader with versioned page/paragraph anchors | No paywall bypass; embedded PDF text only; no OCR/table-structure guarantee |
| Evidence workflow | Claim records, exact-quote references, owner assessments, source independence acknowledgment, automated assessment drafts using chosen model | Manual assessment is attributed to the owner; automated drafts never set truth status |
| Connections | Typed, cited, directional relationships; timeline/list; remove/split operations; conservative story candidates and owner confirmation | Title/time similarity is only a candidate, not event identity or independent corroboration |
| Corrections | Article-version archive; changed source invalidates cached model outputs/vectors and marks dependent review records stale; correction/withdrawal alerts | No global publisher-retraction service; changes known to this app only |
| Personalization | Explicit feedback with short/long decay; ordering only inside user-selected layers; source diversity within a layer | No automatic rewriting of pinned cores; feedback is not a measure of understanding |
| Watchlists | Names and aliases, word boundaries, deterministic version-aware alert deduplication | User-supplied aliases are not entity resolution; inspect ambiguous matches |
| Semantic retrieval | Selected embedding route indexes up to 20 records; model/dimension/source-version compatibility checks; optional reranking route | Requires real compatible models; not automatically enabled or quality-benchmarked |
| Comparison | Selected research records and evidence-backed cells; absent data stays unknown | Does not infer winners or comparable benchmark conditions |
| Interoperability | Saved bibliography export as BibTeX/RIS; annotations included in JSON export | Metadata may be incomplete; no invented DOI or author details |
| Offline edits | Web local queue and native SQLite queue; replay IDs; note-base conflict rejection; manual conflict inspection/discard | Full multi-user or general field-level CRDT merging is not implemented |
| Model controls | Per-provider input/output budgets, temperature, timeout; daily cloud request ceiling; background jobs; observable file download counts | Request count is not an exact money budget; cancellation is cooperative at operation boundaries |
| Host hardening | Bounded HTTP threads, socket inactivity timeout, password failure throttling, isolated bounded PDF parser, reverse-proxy deployment files | Still single-owner; an internet-facing production rollout needs load/security review |

## Use the evidence workspace

Run the host using the existing README instructions (`pip install -r requirements.txt` now includes `pypdf`). Open **Evidence workspace** in the desktop sidebar, or **Preferences → Open evidence workspace** on a small screen.

1. Open an article → **Full document**. Choose a direct public HTML or PDF URL and confirm personal storage permission. Rights acknowledgment is a user statement, not a license checker.
2. Refresh or wait for the document job. The document shelf offers a text reader and a PDF download where applicable.
3. Each extracted passage retains document hash, paragraph ID, and PDF page when available. **Highlight / cite** creates a version-specific quote and note.
4. Record a checkable claim, then review it with exact evidence passages. `excerpt` refers to title + feed excerpt; document passages use displayed passage numbers.
5. For independent corroboration, supply evidence from at least two source hosts and explicitly confirm independence. Different domains alone do not establish independence.
6. Connect earlier and later records with a typed relationship and cited passage. Topic similarity may be uncited because it asserts only a subject relationship.
7. Story suggestions use conservative title overlap and a 14-day window. Inspect and confirm or remove candidate groups. They never silently establish causation or truth.

Source changes leave historical anchors intact but mark affected assessments as needing reassessment. Deleting a stored document removes its binary if no other document uses it, and marks dependent evidence stale. Re-fetching identical bytes does not create a second document for the same article.

## Search, model controls, and jobs

**Model studio → Add/Edit model** now exposes input character budget, output tokens, temperature, and API timeout. No cloud transfer occurs without the existing consent/local-only/fallback checks. The default daily ceiling is 100 cloud attempts per UTC date; failed attempts also count because a provider may charge for them. This is a request limit, not a cost estimator.

**Evidence workspace → Index collection** calls your selected embedding model. **Search evidence** can use lexical matching, compatible semantic vectors, and optional reranking. A raw pair-classifier label is not a normalized relevance score; a reranking route without the expected score contract is rejected. Re-index after model/provider changes. Hash/model/dimension mismatches are excluded from retrieval.

`POST /api/model-jobs` runs an inference operation in the bounded processing pool and stores the result with its job. Use the synchronous playground when tracking is paused. Job cancellation takes effect at safe boundaries: it does not instantly interrupt an in-flight upstream call, native inference kernel, or currently downloading file. Partial downloaded files can remain. A source document over 24,000 characters can be summarized in up to ten chunks; the response reports truncation beyond 160,000 characters and does not claim a validated global synthesis.

## Offline behavior

On a secure origin, the web service worker caches the updated module shell. Source excerpts and saved notes are cached in browser storage. Eligible profile/save/history edits are queued locally with a stable operation ID. Reconnection replays them in order; the host prevents duplicate operations. Note edits carry the base note and stop on a conflicting host change instead of overwriting it silently. Inspect pending edits in **Evidence workspace → Offline edits** and preserve conflicting notes before discarding a queue.

The Android client uses a SQLite pending queue and a private cached collection. Sync first replays pending operations, then refreshes the cache. Host rejection/conflicts remain queued. The app does not infer full-paper reading from opening a publisher link.

## Build and install Android

The native project is under `android/`. Open it in Android Studio with Android SDK 35 and JDK 17, or use Gradle 8.11.1:

```bash
gradle -p android assembleDebug testDebugUnitTest
```

The GitHub **Android build** workflow compiles and runs unit tests and uploads a `samacha-RAM-debug-apk` artifact on success. A successful compile is not a physical-device or model-quality test. Debug APKs are for testing; no Play Store release or release signing is configured.

On an emulator, connect to `http://10.0.2.2:8787` and include `10.0.2.2` in the host's `SAMACHA_ALLOWED_HOSTS`; expose beyond loopback only with a password. Release builds require HTTPS; cleartext is allowed only in the debug manifest. The device connects to the computer's host, not its own localhost.

In **Models**, each task independently selects a host route or a device bundle. Host providers are registered using the host's encrypted vault. Device routes execute locally and never silently fall back to a host/cloud endpoint.

### ONNX classifier / embedding bundle

Import each file into the same named bundle:
- `model.onnx`: dynamic-sequence uncased BERT model using int64 `input_ids`, optional `attention_mask`/`token_type_ids`.
- `vocab.txt`: matching uncased WordPiece vocabulary.
- `config.json`: `model_type: "bert"`, explicit `do_lower_case: true`, and `id2label` for a fine-tuned classifier.

Classification reads logits; embeddings use a pooled vector or mean-pool the returned sequence representation. A sentiment model's labels remain sentiment labels; this adapter does not pretend it is a domain classifier. BERT tokenizer tests cover supported English uncased tokenization, unknown pieces, and truncation. Other tokenizer/input architectures must use another evaluated adapter.

### Local text-generation bundle

Import a compatible `model.litertlm` into a named bundle and assign it independently to summarization, QA, or evidence draft tasks. The pinned LiteRT-LM Android adapter follows the official Engine API. Import has a per-file 4 GiB limit, total 8 GiB quota, hashes, and free-space checks. Runtime memory checks are approximate; real devices can still lack adequate RAM or compatible operations. No model file or paid key is bundled or downloaded automatically.

The current UI supports imports from the Android document picker. Download a licensed compatible model with your chosen trusted source/browser first, then import it. Host-managed pinned downloads remain available in the web Model studio. Direct managed phone downloads with pause/resume are not implemented.

## Deployment preparation

`Dockerfile`, `compose.yaml`, and `deploy/Caddyfile` provide a non-root app container behind a TLS reverse proxy. Set a strong `SAMACHA_PASSWORD` and an owned `SAMACHA_DOMAIN` before running Compose. Only the gateway exposes ports. No deployment, DNS change, or service purchase was performed.

The base container excludes heavy model runtimes. Loopback Ollama on a host is not the same as loopback inside a container; use the direct host installation for that configuration. An optional model-runtime container/device design needs explicit networking and resource decisions rather than weakening the private-network proxy safeguards.

## Remaining validation and scope

- Test on physical Android hardware, including real imported model weights, memory use, battery behavior, notification delays, TalkBack, and network loss.
- Benchmark real model quality, citation semantic support, embedding retrieval, ranking usefulness, and source independence with a reviewed corpus.
- Live-source access, rights, and license coverage need source-by-source checks. No universal truth guarantee or complete global retraction monitoring is possible.
- OCR, precise PDF bounding boxes, automatic research-status reconciliation, source-specific table extraction, managed phone download pause/resume, fully encrypted document storage, multi-tenant authorization, and production-scale measurements remain outside this implementation.

These are explicit limitations. Do not describe this release as 100% complete or independently fact-verified.
