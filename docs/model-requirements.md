# User-controlled specialist models

Added 18 October 2026 from the user's instruction for `samacha-RAM`.

## Requirements and implementation

| ID | Requirement | Current implementation |
|---|---|---|
| MOD-01 | Never depend on one model for every purpose | Independent classification, summarization, embeddings, reranking, evidence, and QA routes |
| MOD-02 | User supplies API keys | Encrypted key vault; edit/keep/remove key; no frontend echo or export |
| MOD-03 | Use downloaded open-source models | Import a compatible safetensors bundle; pinned Hub download; optional CPU Transformers runtime |
| MOD-04 | Task-specific specialists | Capability-filtered route selectors; distinct task adapters and prompts |
| MOD-05 | Local/open-weight choice | Transformers app-host execution; loopback Ollama; llama.cpp compatible endpoints for GGUF |
| MOD-06 | User chooses fallback behavior | Per-task fallback plus provider cloud consent, local-only policy, and global cloud-fallback gate |
| MOD-07 | Show actual model behavior | Inference test and six-task playground; provenance, latency, local/API origin, and fallback status |
| MOD-08 | Downloads respect user control | No automatic downloads; revision/size/format checks, license acknowledgment, observable queued/complete/failed/interrupted states |
| MOD-09 | Avoid provider lock-in | Configurable compatible endpoints; no hardcoded provider/model default |
| MOD-10 | Do not equate model output with truth | Evidence draft labels, exact-quote validation, uncertainty notices, no truth badge |

## Format/runtime compatibility

“Open source” does not imply interchangeable file formats or task architectures. The selected model's license, tokenizer, context size, hardware/RAM, architecture, and task all matter. A classifier must expose meaningful topic/NLI output. A sentiment classifier is not automatically a domain classifier. A summarizer should support summarization. Chat generation and embeddings use different API payloads. Pair classification labels are not normalized without an explicit label schema.

v0.2 adds a native Android client: supported ONNX uncased BERT tasks and LiteRT-LM bundles can run directly on the phone. The host Transformers and Ollama adapters remain host-local. Physical-device model quality, battery, and RAM testing are still required; see release-v0.2.md for exact format contracts.

## Future model controls

Per-task context/output budgets and sampling parameters; streaming cancellation; model quality benchmarks; tokenizer-aware chunking; trustable semantic citation alignment; custom label schemas; task-specific ONNX support; GPU/device selection; fine-grained provider cost limits; gated-model authentication; resumable/abortable downloads; native Android storage/Keystore and on-device inference. These are not implemented in this release.
