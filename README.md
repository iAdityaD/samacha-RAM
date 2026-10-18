# samacha-RAM

**Your interests. Your sources. Your models.**

A self-hosted, single-owner research and news workspace based on the InterestAtlas v2 specification. The responsive interface works on desktop and Android browsers and includes an installable PWA shell. This release is a working foundation, not the entire production roadmap or a native Android APK.

## Run

Python 3.12+ is recommended. The basic reader needs only `cryptography`; no Node build step or AI subscription is required.

```bash
git clone https://github.com/iAdityaD/samacha-RAM.git
cd samacha-RAM
python -m venv .venv
# Linux / macOS
source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m server.app
```

Open **http://127.0.0.1:8787**. The app initializes a clearly labeled historical reading collection; click **Refresh sources** to gather current publisher feed entries. Failed sources retain their previous records and show an unavailable state. No synthetic headlines are presented as live news.

Data lives under `data/` by default (override with `--data` or `SAMACHA_DATA`). This directory contains private records, the encryption key, and optional model files; it is ignored by Git. Back it up securely. The encryption key protects API-key fields, not the whole SQLite database or browser cache.

## What works

- Today feed with **For you / Following**, topic and content-type filters, search, explicit Core/Near/Adjacent/Discovery/Muted preferences, and recommendation reasons.
- RSS, Atom, and JSON Feed ingestion; URL deduplication; source health; source-content hashes; source toggles; OPML preview/import and export.
- Historical starter research records and live research-feed records with honest publication/evidence/access labels.
- Article excerpts, original-publisher links, deterministic extractive overviews, selected-model summaries, source-scoped Q&A, saved records, persistent notes, and explicit reading-event history.
- Topic-only related items, labeled as similarity rather than citations, causal links, or verified follow-ups.
- Temporary research missions that filter the collection without rewriting global interests.
- Saved searches, JSON export, finite time-budgeted briefings, and a daily in-app refresh/briefing routine while the host runs.
- Light/dark themes, responsive navigation, keyboard search, offline shell, and a cached excerpt collection.
- **Model studio:** per-task primary/fallback assignments; API keys encrypted at rest; provider-specific consent; local-only policy; cloud-fallback gate; actual inference tests; observable processing activity.
- Model-folder upload/import and pinned Hugging Face model downloads with safetensors-only loading and optional CPU execution on the app host.

## Independent models for independent tasks

| Task | Deterministic baseline | Optional specialist |
|---|---|---|
| Classification | Topic keyword rules | Downloaded zero-shot NLI classifier or a configured API model |
| Summarization | Source sentence extraction | Downloaded summarization/seq2seq model, Ollama, or compatible chat API |
| Embeddings | Explicitly unavailable | Downloaded feature extractor, Ollama embed API, or compatible embeddings API |
| Reranking | Explicitly unavailable | Pair classifier or prompted API relevance score |
| Evidence review | Explicitly unavailable | Pair classifier or cited API assessment draft; **never an automatic truth badge** |
| Q&A | Matching source passages | Downloaded extractive QA model or cited API answer |

No model is hardwired across all tasks. Each task has its own primary and fallback. A configured provider must declare the task before it can be assigned. Capability declarations are user assertions until tested; the model test exercises its first declared task, not every capability or quality criterion.

Classification uses the selected route for a bounded batch of up to ten unprocessed source records per refresh; remaining records retain source/keyword topics. Summarization and Q&A use the selected routes from the article view. All six tasks can be run directly in Model studio. Embeddings and reranking are executable tasks, but are **not yet integrated into semantic search or feed ranking**.

### API providers

1. Open **Model studio → Add model**.
2. Select **OpenAI-compatible API / llama.cpp**.
3. Enter the provider model ID, API base URL (including `/v1` where applicable), and optional API key.
4. Choose its capabilities and explicitly allow cloud text transfer for a cloud provider.
5. Save, assign it to a task, and run **Test inference** or the task playground.

This adapter implements compatible chat-completions and embeddings protocols. It does not claim native compatibility with every provider API (for example, provider-specific Messages APIs). Public endpoints must use HTTPS. The UI never calls providers directly; source text goes from your own host to the endpoint you configured. Keys are never returned by the API or included in exports.

### Local Ollama or downloaded GGUF models

Run an independently installed Ollama or llama.cpp server on the **same computer as the app host**. Pull/download a compatible model using that runtime's documented installation method; no external runtime is installed automatically.

- Ollama: adapter `Ollama`, endpoint `http://localhost:11434`, exact installed model name.
- llama.cpp: adapter `OpenAI-compatible API / llama.cpp`, endpoint such as `http://localhost:8080/v1`, model alias from that server.
- Check **Local endpoint**. Loopback destinations are allowed only through this explicit mode. Private LAN/private-cloud endpoints are intentionally not supported by the outbound proxy in this release.
- GGUF files are handled by your local llama.cpp runtime; they are not loaded by the Transformers adapter or uploaded as safetensors bundles.

The local runtime's context, RAM, model format, chat template, and task capabilities must match the selected route. A local chat model is not automatically a suitable embedding model.

### Download or import Transformers specialists

Install the optional runtime on the app host:

```bash
python -m pip install -r requirements-models.txt
```

Then choose **Model studio → Download or import**:

- **Download:** enter the Hugging Face `owner/model` identifier and exact 40-character commit revision. Review its license, architecture, and expected size before confirming. Public, accessible bundles are supported; gated-model authentication is not implemented. The managed download limit is 4 GiB. Downloads do not execute model repository Python code.
- **Import folder:** select an already downloaded complete model directory. Only `.json`, `.txt`, `.model`, and `.safetensors` files are uploaded to your app host. Executable code and pickle weights are not accepted. Existing files are not overwritten; use a different bundle name for a new version. The managed import quota is 8 GiB. Registration requires `config.json` and safetensors weights.
- Alternatively, copy a complete trusted bundle into `data/models/<bundle-name>/` on the host.
- **Register:** Add model → Downloaded Transformers bundle → exact bundle folder name → one task. Register separate records for other tasks.

Task architectures: zero-shot NLI for classification; summarization-compatible seq2seq for summarization; a feature-extraction encoder for embeddings; extractive QA for local questions; pair sequence classification for reranking/evidence. Pair-classifier labels are shown raw because their meaning differs by model. They are not silently mapped into “true/false.” Custom remote-code architectures and arbitrary model formats are unsupported.

Models run on CPU in a serialized local inference lane; only one pipeline is retained in memory. Hosted/API tasks have a maximum of two concurrent inference requests. Large models may exceed available RAM. Transformers-generated summaries are labeled as uncited drafts; API summaries/answers must return quotes found in the actual supplied source. Exact-quote validation is not a semantic correctness guarantee.

**No paid API call, large model download, or real model inference was performed during development.** Those paths need a configured model and validation on your hardware. Protocol behavior, routing, privacy gates, validation, and error handling are covered by deterministic tests.

## Android and offline use

Use the browser's **Install app / Add to Home screen** on a secure origin. On localhost, the PWA can cache its shell. Accessing a computer's plain HTTP LAN address from a phone is not a secure origin and may not allow service workers/installability; use a trusted HTTPS reverse proxy for that setup.

The backend is required for mutations, feed refresh, authentication, and inference. When it is unavailable, the last cached source excerpts, saved items/notes, and preferences can be viewed. Offline edits, background push notifications, and native on-phone model execution are not implemented. This is **not** a native Android package, and downloaded models run on the app host rather than in the phone browser.

## Authentication and hosting

The default bind is loopback only. It is a **single-owner** workspace: all signed-in clients share the same collection, models, notes, and preferences. Do not use it as a multi-tenant service.

For remote access, set `SAMACHA_PASSWORD`, explicitly list your domain/IP in `SAMACHA_ALLOWED_HOSTS`, and use a TLS reverse proxy. Set `SAMACHA_SECURE_COOKIE=1` behind HTTPS. Example environment names and values are documented in `.env.example`; the server reads process environment variables, not `.env` files automatically.

The built-in Python HTTP server is a personal/local development host, not a hardened public deployment server. Never expose it directly to the internet. Password rate limiting, multi-user authorization, native background scheduling, comprehensive attachment scanning, and a production deployment gateway remain roadmap items. `vault.key` must not be shared or committed.

Daily scheduling persists the chosen timezone/time and an enabled flag in SQLite. Once enabled, the host checks every 30 seconds, performs a bounded refresh, then builds one in-app briefing per local date. It catches up after a missed chosen time while running; it does not send email or push alerts.

## Validate

```bash
python -m unittest discover -s tests -v
npm test
npm run check
python -m compileall -q server
```

Node 20+ is needed only for development tests; there is no frontend dependency installation or build step. CI runs Python and Node tests. Test details, limits, architecture decisions, and requirement coverage are in `docs/`.

## Important limits

This release does not claim automated factual verification, completed editorial review, full-text extraction, a research citation graph, meaningful story-evolution clustering, PDF download management, learned behavior adaptation, multi-device offline conflict resolution, or the whole v2 roadmap. It includes explicit source-access and uncertainty labels rather than fake implementations of those features. The original v2 requirements and the additional model requirements are preserved in `docs/requirements.md` and `docs/model-requirements.md`.

Provider API implementations were consulted against official [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/master/tools/server), [Ollama chat](https://docs.ollama.com/api/chat), and [Ollama embed](https://docs.ollama.com/api/embed) documentation. Downloaded model behavior follows [Transformers pipelines](https://huggingface.co/docs/transformers/main_classes/pipelines) and [Hub downloads](https://huggingface.co/docs/huggingface_hub/en/guides/download). Always evaluate the particular model and source permissions you intend to use.
