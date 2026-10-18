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
- Human visual review, screen-reader accessibility, mobile installation, offline service-worker lifecycle, long-running import handling, and reverse-proxy TLS. Automated browser coverage is recorded below.
- Model quality, semantic support of generated statements, production load, licensing clearance for individual sources/models, or the entire v2 roadmap.

The GitHub workflow runs the deterministic Python and Node checks on pushes/PRs. A configured model's Test inference is a real request on the user's host; it does not certify the model's factual quality.


## v0.2 validation update

The expanded local suite covers actual fixture-PDF parsing in a subprocess, source-version archives, stale evidence invalidation, no-quote rejection, independent-origin requirements, document rights gates, deterministic watch deduplication, exact annotation references, comparison provenance, model-space isolation, offline idempotency and note conflicts, cancellation flags, request budgets, story candidates, and exposure-aware change notices. JavaScript tests additionally cover offline overlays/conflicts, feedback precedence, and within-layer source diversity.

The Android CI build was exercised on GitHub rather than an unavailable local Android SDK. SDK package and Kotlin/Compose compatibility failures were corrected, and an Android build with native tokenizer unit tests succeeded. The application, Android, and browser workflows check changes on GitHub. No physical Android device, pretrained model weights, or paid API key was used. A separate GitHub Chromium run passed onboarding, source opening, excerpt summarization, persistent notes/library, interest editing, evidence claim creation, model playground, and responsive navigation checks. It checks for page errors and horizontal overflow at 390 px and captures desktop/mobile screenshots as workflow artifacts; those screenshots have not received human visual review. The complete remaining validation boundary is in release-v0.2.md.

Final local validation for v0.2: **62 Python tests and 17 JavaScript tests passed**, with JavaScript syntax checks. The Android workflow also builds and runs **3 native tokenizer unit tests**; the final commit status is available in GitHub Actions.

Automated browser validation passed on implementation commit `bec030adeafec687fb27a7e60daea0cc7dd4379b`: [Browser checks](https://github.com/iAdityaD/samacha-RAM/actions/runs/39132492173). Screenshots are available in its `browser-layout-checks` artifact.
