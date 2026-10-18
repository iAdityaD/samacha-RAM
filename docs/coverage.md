# Requirement coverage and next milestones

Status is about actual behavior in this repository, not feature descriptions in referenced projects.

| Requirement area | Status | Evidence / limitation |
|---|---|---|
| Layered explicit preferences | Implemented | Pure ranking tests; mute dominates core; persisted preferences |
| For You / Following | Implemented | Distinct ranked and chronological filters; no inferred-interest learning |
| Mission isolation | Implemented | Per-mission topic filter, expiry, deletion; global-profile invariance tests |
| Public feed ingestion | Implemented | RSS/Atom/JSON parsing, custom public feeds, DNS checks, source status; publisher access not verified live here |
| OPML portability | Partial | Preview and selected import/export; folder hierarchy is not preserved |
| Search / watchlists | Partial | Text search and saved queries; no entity alias resolution or change alerts |
| Research library | Partial | Metadata, original access, honest status, saved notes; no full-text parser/managed PDF download queue |
| Reading history | Implemented | Four explicit event types, tracking pause, deletion, preserved notes; no inferred full-paper progress |
| Daily briefing | Implemented | Finite time-budget queue and host-running daily routine; no closed-host execution or push notification |
| Version tracking | Partial | Feed content hashes and changed-source marker; no scholarly-work reconciliation or correction/retraction service |
| Story evolution | Deferred | Shared-topic related items are labeled; no event clustering presented as established story relations |
| Summaries and QA | Implemented with limits | Extractive defaults; configurable model drafts; exact-quote validation for generated API citations; no independent semantic validation |
| Factual verification | Deferred | Explicit not-assessed labels and review drafts; no automatic verified badge |
| Model choice | Implemented | See MOD-01–MOD-10 and model router tests |
| Offline | Partial | Cached shell/excerpts/saves; mutations need host; no offline mutation queue/conflict resolution |
| Export | Partial | Library JSON and feed OPML; BibTeX/RIS and annotation anchors deferred |
| Privacy and host boundary | Implemented for local single-owner use | Encrypted keys, session/Host/origin checks, DNS-pinned egress; public production hardening remains |
| Android native | Deferred | Responsive installable web app only; no APK or native on-phone runtime |
| Graph/RAG comparisons | Deferred | No fake graph, benchmark winners, or citation/causation edges |

## Next implementation milestones

1. Hardened job execution and measured model adapters, with input chunking, cancellation, progress, resource/cost budgets, and model/task evaluation corpus.
2. Full-text adapters with per-source rights and exact document-version/page anchors; lawful PDF download management.
3. Typed story/research relationships, correction propagation, reviewer operations, research comparison matrix.
4. Native Android client and independently validated on-device specialist engines, secure key storage, offline sync, and download lifecycle.
5. Learned short/long-interest signals, source-diversity constraints, entity watchlists, and evaluated semantic search/reranking.

Do not mark the entire requirements document complete when only this foundation has shipped.
