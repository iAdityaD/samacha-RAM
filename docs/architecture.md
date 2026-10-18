# Architecture decisions

## ADR-001: Self-hosted PWA foundation

The selected repository was empty. Build a small responsive installable web client and a Python single-owner host, with no JavaScript build dependencies. This provides a runnable vertical slice without inventing an Android SDK, cloud account, or provider credential. The specification's Android-first direction was a proposed default; a native client remains a separate milestone. Local models in this release run on the host computer. No native on-phone inference is claimed.

## ADR-002: Modules and persistence

`server/app.py` owns HTTP/session boundaries, mutations, refresh jobs, and daily in-app scheduling. `feeds.py` handles feeds and clearly marked historical seed records. `store.py` owns SQLite and the API-key vault. `network.py` bounds and DNS-pins outbound requests. `models.py` contains task contracts, privacy routing, adapters, downloaded-model validation, and model jobs. `web/core.js` contains pure ranking, filtering, expiry, and time-budget functions. `web/app.js` owns the responsive interface; `sw.js` caches only the app shell. The browser separately caches a limited data snapshot excluding provider configurations and reading history.

SQLite uses WAL and a lock per shared connection. Credentials are Fernet-encrypted with a host-local key created with restrictive file permissions. The database is not fully encrypted. No secrets are stored in source, exports, frontend responses, or request logs. Deleting history also deletes saved model-output records derived from reading; saved notes remain.

## ADR-003: Task-specialist routing

Six independent task routes each select primary and fallback IDs. Providers declare capabilities. Cloud text transfer requires provider consent; local-only blocks cloud primary/fallback before network access; cloud fallback needs a second global opt-in. There is no global “one LLM for everything” configuration.

Adapters: compatible chat/embeddings APIs, loopback Ollama chat/embed, downloaded Transformers pipelines. GGUF is delegated to a separately installed llama.cpp runtime. A task error stays visible; no fake scores/vectors/verification are substituted. Classification and extractive summary/Q&A have useful deterministic baselines. Local model loading disables repository code, requires safetensors, confines paths, rejects symlinks, and serializes pipeline loading/inference.

## ADR-004: Evidence boundaries

Evidence is always not assessed until an actual review process exists. API generated citations must quote actual source substrings; this detects nonexistent quotes but does not prove semantic support. Local abstractive summaries are explicitly uncited drafts. Evidence pair-classifier output is a review aid with raw labels, not a truth verdict. Related content uses shared topics and never claims citation, causation, or factual follow-up.

## ADR-005: Access and network boundaries

Default loopback binding. Optional password sessions are single-owner, HTTP-only, SameSite=Strict. APIs require a session and same-origin custom-header mutations. Incoming Host is allowlisted. Public outbound destinations require HTTPS, globally routable DNS results, TLS verification, size bounds, and no redirects. DNS is validated and the socket connects to that exact IP. Explicit local providers are restricted to loopback on the app host. No public arbitrary private-network proxy.

This is not a hardened multi-user/public server. Production auth/rate limiting, TLS termination, job isolation/resource limits, source licensing review, and operational monitoring need a separate release decision. Local model files must be trusted; safetensors and remote-code denial reduce but do not eliminate parser/runtime risk.

## ADR-006: Progressive enrichment

Feeds remain readable when optional AI libraries or providers are absent. Classification applies only to ten unprocessed source records per refresh, with version-based reuse for unchanged records. Overview and Q&A are on demand. No model or paid request is activated by default. Managed downloads are revision-pinned and size-checked. Imported files are streamed, hashed, confined to a new model-bundle path, and quota-checked. Downloads are not yet cancellable; interrupted jobs are marked interrupted on restart.

## ADR-007: Scope and exit path

The host is intentionally single-user. APIs and pure ranking logic can support a future native client, but this release does not supply native bindings. A later backend can replace JSON-body SQLite tables with typed migrations and multi-tenant authorization. Provider interfaces can be extracted without changing per-task routing semantics. The complete future scope is preserved in requirements, with implementation status in the coverage document.
