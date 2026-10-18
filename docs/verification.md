# Verification record

Development environment: Python 3.12, Node 24. No production services, API keys, GPU runtime, or downloaded model weights were supplied.

Checks run locally:
- Python compile checks for all server modules.
- Python unittest suite covering feed parsing, stable IDs/content versions, unsafe XML/URLs, unknown dates, persistent saved notes, key encryption/redaction, history deletion/tracking, per-task routing, capability mismatches, cloud consent, local-only blocking, cloud fallback denial, exact citation validation, embedding validation, bundle constraints, Host/origin/session controls, logout, and HTTP reading workflows.
- Node tests for layer precedence, chronological following, query filtering, duplicate handling, mission isolation/expiry, finite reading budgets, shared-topic relations, and safe rendering primitives.
- A DOM-contract smoke test executes the real frontend module, renders all primary screens, opens an article, and saves a record through mocked API contracts. This is not browser layout testing.
- JavaScript syntax checks for all app modules and service worker.

Not validated here:
- Live publisher availability or provider calls through this environment's restricted network.
- Actual Hugging Face weight download, compatibility, RAM use, or real model inference.
- Browser visual layout, screen-reader accessibility, mobile installation, offline service-worker lifecycle, long-running import handling, and reverse-proxy TLS.
- Model quality, semantic support of generated statements, production load, licensing clearance for individual sources/models, or the entire v2 roadmap.

The GitHub workflow runs the deterministic Python and Node checks on pushes/PRs. A configured model's Test inference is a real request on the user's host; it does not certify the model's factual quality.
