# InterestAtlas — End-to-End Requirements and Development Prompt

Version 2.0 · Product specification and GitHub-informed design review · 17 October 2026

Revision note: preserves the original requirements and adds a 25-repository survey, 18 concrete enhancements, revised implementation priorities, and an expanded development prompt. Sections 21–27 define the v2 additions; where priority or delivery sequencing differs, Section 26 takes precedence. Repository capabilities are documentation observations, not independently reproduced results.

## 1. Product purpose

Build a personal knowledge and news application that gathers research papers, journal articles, technical blogs, and news from permitted public sources; organizes them around evolving user interests; explains the available evidence; and connects new developments with earlier reading.

InterestAtlas is a working name. This document defines the product, not an authorization to implement or deploy it. Proposed defaults are changeable before implementation.

The central promise: “Follow what matters to you, understand the evidence, and see how developments connect.” Never promise 100% factual coverage. The product must reveal uncertainty, incomplete access, contradictory evidence, corrections, and publication status. An announcement is evidence that an organization made a claim; it does not independently prove the claim.

“Journals” means research papers and journal articles in this specification, rather than personal diary entries. News gathering is server-assisted; saved reading works offline. Android-first is a proposed client direction, with backend APIs reusable by web and other clients. English-first, with language preferences and translations designed for later expansion.

## 2. Users and boundaries

Primary users: learners, engineers, researchers, and technology enthusiasts who follow several changing areas and want a daily, evidence-aware reading workflow.

Roles:
- Reader: configures interests, follows sources, reads, saves, downloads, and manages personal data.
- Curator: reviews source suitability, taxonomy mappings, story merges, and disputed claims.
- Administrator: manages access, service health, connector credentials, budgets, and audited operations.

Included: discovery, evidence presentation, research tracking, summaries, daily briefings, story timelines, history, privacy controls, and source operations.

Excluded from the first release: social posting, public comments, trading advice, unrestricted web crawling, paywall bypass, automatic truth certification, and training models on user reading without consent. No provider or publisher partnership is assumed.

## 3. Information model: keep these concepts separate

| Concept | Meaning |
|---|---|
| Topic | Subject such as AI, NLP, AI audio, hardware, or software engineering |
| Content type | News, research paper, technical blog, tutorial, editorial, announcement, review, survey, or release note |
| Entity | A model, organization, author, dataset, product, technique, or benchmark |
| Article | One published item at one source |
| Work | Research identity grouping preprint, accepted manuscript, and published versions when verified |
| Story | One event or development discussed by several articles |
| Claim | A specific checkable statement with scope and conditions |
| Evidence | A cited passage, table, dataset, document, or other record bearing on a claim |
| Connection | An explained relationship between works, articles, stories, or claims |

An article can belong to several topics. An editorial can contain factual claims but remains an editorial. A research paper is not automatically peer reviewed, replicated, or correct. Venue and publication status are separate fields.

## 4. Evolving interest graph

### 4.1 Taxonomy

Maintain a versioned directed acyclic topic graph with multiple parents, synonyms, aliases, and user-created topics awaiting mapping. Example branches:
- AI → language technologies → NLP → LLMs → retrieval, reasoning, evaluation, agents.
- AI → audio → speech recognition, synthesis, music generation.
- AI → video → generation, understanding, editing.
- Hardware → accelerators → GPUs, NPUs, inference hardware.
- Software → languages, architecture, databases, distributed systems, developer tools.

Hardware inference optimization can connect to both hardware and AI. Classification stores confidence, model/rule version, and curator/user corrections. Unknown or low-confidence topics remain visible as unresolved rather than forced into a category. Taxonomy edits must preserve historical mappings through migrations.

### 4.2 Personal layers

Each user has independent weights over topics; layers are an understandable view of those weights and topic proximity, not fixed global categories.

| Layer | Purpose | Example for an LLM-focused user |
|---|---|---|
| Core | Explicitly important topics | LLM inference and evaluation |
| Near | Closely related interests | Retrieval, NLP, agents |
| Adjacent | Useful neighboring areas | Accelerators, AI audio |
| Discovery | Controlled exploration | AI video, novel interfaces |
| Muted | Excluded or temporarily paused | Selected topics or sources |

Support multiple core interests, depth controls, per-topic frequency, language, beginner/intermediate/advanced preference, content-type preferences, source preferences, temporary interests, pause/unpause, pin, mute, reset, and a change history.

Default experimental allocation: 55% core, 25% near, 15% adjacent, 5% discovery. These are tunable starting values, not a scientifically validated optimum. Users can change them. When supply is insufficient, show fewer results or transparently backfill permitted layers; never manufacture relevant content.

### 4.3 Learning and drift

Explicit follow, mute, and layer choices outrank inferred behavior. Reading, saving, completing, revisiting, and feedback provide different-strength signals. A click alone is weak evidence. Opening something to criticize it must not automatically make it a core interest. Background time, accidental taps, idle screens, and app exits do not count as meaningful reading.

Inferred weights decay; pinned choices do not decay silently. A change of interest should affect the next feed request, while background recomputation updates caches. Propose major core changes with an explanation and require the user's selection before moving a pinned topic. Offer “Why this?”, “Less like this”, “More like this”, “Already know this”, and “Reset personalization.”

Maintain cold-start recommendations from explicit onboarding choices. Allow a personalization-off chronological mode. Do not optimize solely for clicks or dwell time.

## 5. Gathering and processing content

### 5.1 Source registry

For every source record publisher, domain, source type, connector, supported languages, allowed fetch/extraction/storage/download behavior, polling policy, attribution, owner, provenance, health, last success, and review status. Prefer permitted feeds and APIs; add HTML extraction only where access and reuse rules permit. Public availability does not automatically grant full-text redistribution rights.

User-added URLs enter a validation flow. A source being reputable is a ranking signal, not a blanket truth label. An unknown source is not automatically false. Maintain transparent source inclusion criteria and an appeal/correction mechanism.

### 5.2 Pipeline

1. Schedule source polling within per-source limits and product budgets.
2. Fetch with timeouts, conditional requests, bounded retries, backoff, and idempotent job keys.
3. Validate responses and isolate untrusted input.
4. Extract title, author, source, canonical URL, dates, language, main content, references, identifiers, and rights metadata.
5. Preserve permitted source snapshots or hashes and extraction provenance.
6. Deduplicate exact items, detect syndication, resolve research versions, and propose story clusters.
7. Classify topics/types/entities and extract candidate claims.
8. Retrieve evidence, assign scoped evidence states, and route uncertain/high-impact cases for review.
9. Generate grounded summaries and supported relationship suggestions.
10. Index publishable items, rank them, and prepare daily updates.
11. Revisit changed documents, corrections, and publication updates.

These stages have durable states: discovered, fetching, extracted, enriching, review-needed, published, quarantined, failed, withdrawn. Failures in expensive enrichment must not discard valid metadata. A metadata-only item can be published with a clear “Full text unavailable; summary unavailable” state.

Store published_at, source_updated_at, fetched_at, and first_seen_at separately. Do not disguise an old article as new because it was fetched today. Keep version history for meaningful content changes. Preserve conflicting authorship or dates as unresolved when necessary.

### 5.3 Deduplication

Use normalized canonical URLs, external identifiers, content hashes, title/author/date comparisons, and semantic similarity. Semantic similarity alone cannot merge distinct events. Group copies under a representative article while retaining every source, attribution, and evidence record. Copying one press release across twenty sites does not create twenty independent confirmations. Allow audited split/merge corrections.

## 6. Evidence and factuality requirements

### 6.1 Check claims, not just domains

For each material claim retain exact statement, entities, time, scope, benchmark conditions where relevant, supporting evidence, contradicting evidence, source independence, access limitations, assessment time, and assessment method/version.

Use understandable states:
- Supported by cited primary evidence.
- Corroborated by independent reporting/evidence.
- Reported by one accessible source.
- Disputed or contradicted.
- Insufficient evidence / not assessed.
- Corrected, superseded, or withdrawn.

An assessment must state which claim it covers. “Supported” does not establish universal truth. Model-generated confidence is not a probability that a statement is true. Avoid public numeric truth percentages unless a calibrated evaluation and interpretation have been established.

### 6.2 Verification process

Resolve claim → find original records → inspect supporting passages and context → check independent evidence where available → inspect dates, contradictions, and corrections → record limitations → assign scoped state. Use deterministic validation for identifiers, units, dates, and numeric consistency where possible. LLMs assist extraction and assessment but cannot serve as the sole evidence.

For research, distinguish author-reported results from independently reproduced findings. Preserve dataset, baseline, version, evaluation setup, uncertainty, and limitations. “Outperforms” cannot be generalized from one benchmark. For vendor claims, label commercial origin and independent verification status.

Do not publish unsupported claims as verified while waiting for processing. Users can include unassessed items with visible labels or select an evidence threshold. Even in a strict feed, important disputed developments may appear as clearly labeled disputes if the user enables them.

### 6.3 Corrections

Provide “Report an issue” with disputed sentence and optional evidence. Log review decisions. Correct source records and regenerate affected summaries and story explanations. Notify previous readers when a material correction or retraction is known. Keep the old version and clear change notice; do not silently rewrite history. Distinguish “source removed” from “claim disproven.”

## 7. Research papers and journal articles

Research detail includes title, authors, affiliations when available, abstract, topics, identifiers, venue, publication date, version, publication status with provenance, references, citations when supplied, code/data links, access status, and known correction/retraction notices.

Publication states include unknown, preprint, submitted if documented, accepted if documented, and published; peer-review status is separately sourced. Do not infer acceptance from an author's filename or infer paper quality from venue alone.

Download only an accessible, permitted file. Show format, version, size when known, and source before downloading. Validate type, integrity, and safe filename; track pause/retry/cancel, offline availability, storage limits, deletion, and failure reasons. Never bypass a paywall. Where unavailable, offer the official page or a verified permitted manuscript version. Metadata export is available even when the full paper cannot be downloaded.

Reader supports document navigation, search where text exists, citation copy, bookmarks, highlights, notes, and resume position. OCR is optional and labeled; uncertain OCR must not support strong numeric claims without checking the underlying page.

## 8. Summarization

User options: quick overview, standard summary, detailed explanation, technical research breakdown, and “what changed since the previous item.” Reading-level preferences affect explanation without changing meaning.

News/blog output: what happened, key claims, evidence state, why relevant to the selected interests, prior context, uncertainties, and original links. Keep opinions and interpretation labeled.

Research output: problem, method, data, author-reported results, evaluation conditions, limitations, publication status, and code/data availability. Do not invent missing details.

Every substantive factual summary claim links to its source passage/page or accessible evidence. A summary of an abstract must say “Based on abstract only.” Inaccessible full text must not be guessed. Preserve numbers, units, negation, uncertainty, and temporal context. Quarantine or reject outputs that fail citation-support checks. Citation checks reduce risk but do not guarantee correctness.

Cache by document version, summary style, language, and generation configuration. A source update invalidates dependent summaries. Show generation date and source version. User-created notes remain separate from generated text. Translation labels preserve source citations and original access.

## 9. Connected news and knowledge history

Offer two distinct sections: “Related developments” across the corpus and “Connected to what you read” based on the user's history. Never imply a user read an article because it was shown or its summary was opened.

Relationship types: same event, follow-up, earlier background, update, correction, contradiction, explicit research citation, replicated result, superseding version, and topic similarity. Topic similarity alone cannot become causation, replication, or contradiction.

Each edge stores direction, type, rationale, evidence references, confidence, creation method, version, and review state. Display an explanation such as “This follows the model release you read last week; the new article reports an independently measured latency test.” Only show this wording when supported by actual records.

Story pages provide a dated timeline, representative sources, key changes, unresolved disagreements, and publication/update times. Related items outside followed interests can appear in this context without automatically becoming future feed preferences. Users can reject a connection; preserve feedback for evaluation.

## 10. Reading, history, and organization

Track impressions, opening, summary opening, original-link opening, foreground reading sessions, progress, completion, saves, downloads, revisits, highlights, notes, and explicit feedback as separate events. Minimize collection and document what each event means.

Reader features: clean permitted reading view, original-source access, text sizing, light/dark themes, accessible controls, estimated reading length, progress restoration, bookmarks, collections, notes, highlights, and optional offline saving according to rights.

History supports search by date/topic/source/type, continue reading, completed/in-progress filters, manual mark read/unread, delete selected history, clear history, export, and incognito reading. Opening an external publisher page records only that link opening unless additional progress is actually observable with consent. Do not claim knowledge of reading performed outside the app.

Deleting history must remove its personalization contributions through recomputation or an equivalent documented mechanism; invalidate cached feeds and history-based connections. Keep explicitly saved collections unless separately deleted. Explain applicable operational backup retention.

## 11. Main screens and user journeys

Proposed bottom navigation: Today · Explore · Research · Library · Profile. Global search and notifications are available from primary screens.

| Screen | Required behavior |
|---|---|
| Onboarding | Choose topics, core/near/adjacent layers, depth, language, content types, optional daily time, and tracking choices |
| Today | Daily briefing, personalized feed, layer/content/evidence filters, chronological option, why-this explanations, refresh status |
| Explore | Browse topic graph, find sources/entities, inspect current topic pages, adjust interests |
| Research | Papers, publication/access filters, version-aware detail, summaries, permitted downloads |
| Article/reader | Attribution, dates, evidence scope, summary/full text status, save/read controls, connections, report issue |
| Story | Timeline, source comparison, changed claims, corrections, previous-reading links |
| Library | Saved articles, papers, downloads, collections, notes, and reading history |
| Profile/settings | Interest map, feedback controls, privacy/export/delete, notifications, cache and downloads |
| Curator/admin | Source health, disputed items, extraction errors, story corrections, access audit, job and cost monitoring |

Core journey: choose LLMs as core and hardware as adjacent → receive sourced content → read a paper → save its permitted PDF → see a later benchmark linked to that paper → adjust hardware to core → receive the updated mix → see a material correction if later identified.

Every screen needs loading, empty, offline, partial-data, permission-denied, and retry states. Do not make artificial content appear live. Label sample/demo records clearly.

## 12. Ranking and daily updates

Apply hard filters first: access/safety policy, explicit exclusions, language settings, content availability, and chosen evidence threshold. Rank eligible items using interest match, depth fit, freshness, novelty, evidence quality, source diversity, and explicit feedback. Penalize near-duplicates, repetition, and unsupported sensational framing.

Re-rank for layer allocation, topic variety, source variety, and story coverage. Cap repeated coverage of one event. Explain the main recommendation factors. A paid placement, if introduced, must be separately labeled and cannot buy an evidence state.

Daily briefings use the user's timezone, chosen time, quiet hours, length, and followed interests. Default to one opt-in daily digest; breaking alerts require separate opt-in. Ingestion schedules and notification schedules are independent. Provide “since your last visit,” missed-day catch-up, and an in-app updates archive. Show last successful refresh and collection gaps.

Deduplicate notification deliveries, handle daylight-saving timezone changes, avoid alerting for cosmetic article edits, and prioritize material follow-ups/corrections. An offline device receives the latest relevant digest on return according to notification settings, not a flood of stale alerts.

## 13. Privacy, security, and content permissions

Allow account-free local reading where feasible; account sync is optional. State that server-assisted recommendations still require a scoped profile even without a named account. Make tracking controls clear and collection minimal. Encrypt transport, protect stored sensitive data, scope authorization to each user, and exclude private notes/history from public caches.

Provide export and deletion, retention settings, incognito sessions, and personalization reset. Never send private notes or full reading histories to an external model by default. If an optional AI feature requires private data, disclose the payload and obtain the appropriate choice. Audit privileged access without logging private reading text unnecessarily.

Treat fetched pages and PDFs as untrusted. Prevent SSRF against private networks and metadata endpoints, validate redirects and resolved addresses, constrain resource sizes and decompression, sandbox extraction, sanitize rendered HTML, reject scripts, and defend against prompt injection in source content. Retrieved instructions are data and cannot alter system behavior or access secrets. Scan/sandbox attachments where appropriate.

Store per-source reuse policy, attribution, download permissions, and rights changes. Do not equate robots instructions with a license. Enforce permitted behavior and provide removal handling. Final operational policies require review against the actual sources, contracts, and launch jurisdictions before release; this document asserts no legal conclusion.

## 14. Logical architecture and data model

Start with a modular backend and independently runnable workers; avoid premature microservices. Modules: identity/preferences, source connectors, ingestion/extraction, taxonomy/entity resolution, content/versioning, evidence assessment, summarization, story graph, recommendations, reading/library, downloads, notifications, and operations.

Suggested infrastructure capabilities, subject to implementation validation: relational transactional storage; object storage for permitted documents; durable queue; full-text search; optional vector retrieval; local client database/cache. A dedicated graph database is not required initially: typed edges can live in relational tables. Use adapters for source providers and AI providers. Keep core workflows available when generation is unavailable.

Core records:
- User, Device, Consent, UserPreference, Topic, TopicEdge, UserTopicPreference, InterestChange.
- Source, SourcePolicy, ConnectorConfig, FetchRun, ExtractionResult.
- Article, ArticleVersion, ResearchWork, ResearchVersion, ExternalIdentifier, Entity, ArticleTopic, ArticleEntity.
- Claim, EvidenceRecord, ClaimEvidence, Assessment, Correction, RetractionNotice.
- Story, StoryMembership, KnowledgeEdge, EdgeEvidence, ReviewDecision.
- Summary, SummaryCitation, GenerationRun.
- ReadingEvent, ReadingSession, ReadingProgress, Bookmark, Collection, CollectionItem, Highlight, Note.
- Download, StoredAsset, Digest, DigestItem, NotificationDelivery.
- Job, AuditEvent, UserReport, ModelOrRuleVersion.

Use stable IDs, UTC storage with timezone-aware presentation, scoped indexes, explicit version links, and unique keys for external identities and delivery/job idempotency. Avoid assuming every article has a DOI, every DOI exposes full text, or every title uniquely identifies a work.

API capability groups: preferences/interests; topics/sources; feed/search; article/versions/evidence; research/download eligibility; stories/edges; summaries/jobs; reading events/progress; collections/notes; digest/notification settings; export/deletion; curator review/operations. Paginate with stable cursors; support cancellation, rate limits, typed errors, authorization, and idempotency for retried mutations. Long-running operations return a job reference and observable state.

Eventual enrichment is acceptable when visibly pending. User edits and saved state need durable acknowledgment. Sync uses per-record versions and conflict rules: do not blindly choose the furthest reading position, because deliberate backtracking is valid. Preserve concurrent notes or expose a conflict resolution flow.

## 15. Reliability and measurable release gates

Proposed initial targets, to confirm against budget and tested load:
- Cached feed API p95 under 500 ms, excluding device/network latency.
- First usable feed within 2 seconds on the agreed reference device/network.
- Local saved content opens within 1 second on the reference device.
- New eligible content visible within 60 minutes for sources polled hourly; show exceptions and upstream delays.
- Daily digest dispatched within 15 minutes of the chosen time under normal service conditions; external push delivery is not guaranteed.
- 99.5% monthly core API availability as an initial operating objective.
- Zero cross-user access in authorization test suites; zero knowingly unsupported “verified” labels in release fixtures.

Define reference workload and device before treating performance targets as acceptance results. Test ingestion with at least ten times the intended launch daily volume and feed with the expected peak concurrent load plus agreed headroom. Set per-source, per-user, and system AI budgets. Cache repeated work, schedule expensive processing by priority, and degrade to metadata/extractive outputs when budgets or dependencies fail.

Monitor connector freshness, parse failures, duplicate rates, citation coverage, assessed-claim error rate, incorrect story merges, rejected edges, ranking diversity, corrections latency, summary cost, queue age, notification duplication, and user complaints. Evaluate ranking with explicit usefulness feedback rather than engagement alone. Evaluate assessment and summaries against a human-reviewed corpus; report precision and recall by claim class and evidence state. Never market benchmark results as universal accuracy.

## 16. Failure cases and required handling

| Case | Required behavior |
|---|---|
| Source is down or rate limited | Backoff; retain existing items; show stale status; avoid retry storms |
| HTML layout changes | Detect suspicious extraction; quarantine; alert curator; do not summarize navigation as article text |
| Full text/paywall unavailable | Keep permitted metadata; label limited access; use official link |
| Many articles copy one announcement | Cluster copies; retain attribution; count evidence independence correctly |
| Conflicting sources | Present scoped disagreement and citations; do not average into a fabricated consensus |
| Evidence unavailable | Mark insufficient evidence; never invent a source or passage |
| Paper revised or retracted | Preserve versions; update badges/summaries; notify affected readers when known |
| PDF corrupted, huge, or malicious | Stop safely; expose failure reason; enforce limits; offer source page |
| OCR changes numbers | Label uncertainty; check page before strong claims or withhold assessment |
| AI timeout, hallucination, or invalid citations | Fail validation; bounded retry/fallback; preserve reading and metadata access |
| Incorrect story merge or connection | Allow audited split/removal; invalidate downstream explanations |
| Interest changes sharply | Honor explicit change immediately; rebuild feed; retain reversible settings history |
| No relevant content today | Say so; offer older unread or opt-in adjacent content with labels |
| User pauses tracking/deletes history | Stop collection; remove/recompute contributions and affected cached outputs |
| Worker/message retries | Idempotent writes and notification keys prevent duplicates |
| Offline/conflicting device edits | Queue local edits; sync with explicit record-level conflict handling |
| Rights change | Stop new restricted distribution; apply documented retention/removal behavior |
| New source or taxonomy version | Validate before rollout; maintain mapping compatibility and rollback |
| Suspicious source instructions | Treat as untrusted text; deny tool/secret access; log safely |

## 17. Acceptance scenarios

1. A user selects LLMs as core and AI audio as adjacent; every feed item has an understandable topic/layer reason, subject to available supply.
2. Muting a topic removes it from future feeds and digests; explicit settings override old behavioral signals.
3. Reading a hardware article once does not silently make hardware a core interest.
4. Ten copied press releases remain one evidence origin, with source links preserved.
5. A benchmark claim displays model version, conditions, evidence, and author-reported status where applicable.
6. A preprint never receives a peer-reviewed badge without a sourced status record.
7. Abstract-only summaries are labeled and do not invent methods/results absent from the abstract.
8. A restricted paper offers its official page rather than an unauthorized download.
9. A permitted downloaded PDF opens offline and restores reading position.
10. A follow-up links to an actually read item and explains the supported relationship; similarity alone remains “related topic.”
11. A correction updates affected views and gives previously exposed readers an understandable notice.
12. A digest respects timezone, quiet hours, exclusions, and delivery idempotency.
13. Incognito reading creates no server-side reading history or personalization event; permitted operational logs exclude article-level behavior where feasible and disclose limitations.
14. Clearing history removes history-derived recommendations/connections while preserving separately saved bookmarks unless selected for deletion.
15. A source outage, AI outage, or malformed document cannot prevent opening already saved content.
16. A user cannot read another user's notes, profile, history, downloads, or exports by guessing an ID.

## 18. Delivery phases

Phase 0 — Validate: confirm platform, languages, source permissions/connectors, evaluation corpus, launch scale, privacy model, and operating budget. Produce architecture decisions and clickable user flows before major implementation.

Phase 1 — Complete reading loop: limited curated source set; taxonomy and manual interest layers; ingestion and deduplication; sourced metadata; visible evidence/access/publication states; search/feed; reader; saves/history; permitted paper downloads; scheduled daily digest; basic issue review. Use conservative manual evidence review where automation is unavailable.

Phase 2 — Grounded intelligence: cited summaries; claim assessment assistance; version/correction propagation; evaluated story clustering, timelines, and personal reading connections. Expose only relationship types that meet measured quality gates.

Phase 3 — Adaptive personalization: decayed behavior signals, editable interest map, diversity re-ranking, multi-device sync, richer research comparisons, and multilingual support.

Phase 4 — Scale and expansion: more approved connectors, improved research status/retraction coverage, calibrated assessment if feasible, expanded client platforms, and performance/cost work backed by observed demand.

No phase can ship deceptive verification labels. Phasing changes the degree of automation, not the transparency requirement.

## 19. Agent-ready master development prompt

Copy the following prompt together with this entire specification into the implementing agent's context.

```text
Act as a principal product engineer, information retrieval engineer, research-content specialist, mobile UX designer, security engineer, and QA engineer.

Build InterestAtlas according to the attached versioned requirements, including the v2 additions in Sections 21–27. The objective is an evidence-aware personalized reading application covering public research papers, journal articles, technical blogs, and news across evolving technology interests. Preserve all mandatory transparency, source-access, privacy, provenance, and acceptance requirements. Use Section 26 for the revised phase priorities and Section 27 for additional implementation instructions.

Before coding:
1. Read repository instructions and existing implementation. Preserve authorized work and established conventions.
2. Produce a requirement traceability matrix with stable IDs, an architecture decision record, module boundaries, data model, API contracts, user journeys, phased backlog, and risk register.
3. Identify blocking unknowns only. Proposed defaults: Android-first client, English-first content, optional account sync, modular backend with durable ingestion workers, relational storage, permitted object storage, and a small approved source set. Validate currently supported stack versions and actual source interfaces from primary documentation before implementation. Do not assume credentials, source licenses, integrations, or a deployment target exist.
4. Make reversible engineering choices and document assumptions. Do not create unnecessary approval pauses. Obtain missing credentials or decisions only when needed for dependent work.

Non-negotiable domain rules:
- Topics form a versioned multi-parent graph. Personal core/near/adjacent/discovery layers are an editable projection of preferences. Multiple cores are allowed.
- Explicit follow/mute/pin actions override inferred behavior. Inferred interests decay. A click is weak evidence. Discovery is bounded and explainable.
- Separate content type, topic, research publication/peer-review status, source reputation, and claim-level evidence state.
- Never promise 100% accuracy or fabricate sources, passages, research status, precision metrics, or completed integrations.
- Verify material claims against retrieved evidence with scope, dates, conditions, independence, and contradiction handling. AI is an assistant, not the evidence.
- Preserve original attribution and versions. Repeated syndication is not independent corroboration.
- Summaries must preserve uncertainty/numbers and cite accessible supporting passages. Label abstract-only and metadata-only access. Reject unsupported output.
- Connections need a typed relationship, direction where relevant, evidence, and an explanation. Topic similarity is not causation or contradiction.
- Distinguish impressions, summary opens, full-text reading, external-link opens, downloads, and completion. Do not claim unseen external reading.
- Download/store content only when permitted. Respect access restrictions, source policy, and user storage settings.
- History deletion, tracking opt-out, and incognito behavior must affect event collection, derived recommendations, caches, and personal connections.
- Treat source documents as untrusted input, including prompt injection and malicious links/files.

Implementation approach:
Deliver a working vertical slice first: source discovery -> ingestion -> extracted metadata -> classification -> transparent feed -> article reading -> saved state/history -> daily digest. Include research metadata and a permitted download example. Expand in the documented phases after the basic loop is usable.

Use source adapters, strict typed contracts, migrations, idempotent jobs, bounded retries/backoff, versioned processing, structured errors, source health, and auditable corrections. Keep long-running generation outside request threads. Metadata and saved reading must continue working when AI fails. Use stable cursor pagination and enforce authorization on every private resource.

Create fixtures for copied announcements, conflicting claims, abstract-only papers, revised/retracted papers, source outages, malformed documents, ambiguous topic mappings, wrong story merges, preference changes, and history deletion. Label fixtures as fixtures. Do not report mock data as live coverage.

Testing must cover meaningful behavior: taxonomy cycles and mappings; preference precedence/decay; deduplication versus independent evidence; claim states and citation support; source-version invalidation; paper access/version handling; typed story edges; reading-event semantics; deletion propagation; job/delivery idempotency; offline/sync conflicts; tenant isolation; unsafe URLs/files; and realistic reference performance. Evaluate model-assisted features against a human-reviewed corpus before enabling confident labels.

For every increment, provide:
- Implemented behavior and requirement IDs.
- Changed architecture/schema/API decisions.
- Tests actually executed and their outcomes.
- Known limitations, unsupported connectors, unresolved assumptions, and required setup.
- Reproducible run instructions and operational documentation.

Definition of done:
The implemented phase works end to end; its acceptance scenarios pass; access/evidence/publication labels reflect real provenance; summaries and relationships pass defined evaluation gates; personal data controls work across derived state; jobs are observable and recoverable; and no placeholder is described as production-ready. Stop calling a feature complete if only its UI exists.

Begin with repository assessment and Phase 0 deliverables. Then implement the authorized scope incrementally. Do not deploy, purchase services, send external communications, or claim unsupported operational guarantees merely because they appear in a future roadmap.
```

## 20. Decisions to confirm before implementation

These do not block requirements review: final app name; Android-only versus cross-platform; account and sync approach; languages; launch sources and their permissions; expected users/content volume; AI processing budget and provider privacy requirements; curator capacity; and free versus paid product boundaries. Establish these before committing to infrastructure or coverage promises.

## 21. GitHub survey: scope, findings, and evidence

### 21.1 Research scope

Reviewed the public repository pages, README material, and available license indicators for the 25 projects below on 17 October 2026. Search covered personalized news/RSS readers, paper discovery and management, HTML/PDF extraction, recommenders, graph retrieval, story evolution, and evaluation. Selected release pages and explicit limitations were also inspected. This is a broad, relevant survey, not an exhaustive inventory of every GitHub repository. No repositories were installed, executed, security-audited, or benchmarked in this review. README statements remain maintainer claims unless separately tested.

The table distinguishes documented capability from our proposed use. “Candidate” means evaluate behind an interface, not approved for production. License names reflect visible repository declarations at review time; per-file, bundled assets, model weights, dependencies, and later revisions require separate inspection. Unknown licensing blocks code adoption. Software licensing and permission to fetch/store publisher content are independent.

### 21.2 Reader and ingestion references

| ID / repository | Documented capability | Improvement to InterestAtlas | Reuse stance / license observation |
|---|---|---|---|
| R01 [FreshRSS](https://github.com/FreshRSS/FreshRSS) | Self-hosted multi-user feeds, tags, mobile APIs, WebSub, extensions | Portable source subscriptions; push-assisted refresh where supported | Design/API reference; AGPL-3.0 shown |
| R02 [Miniflux v2](https://github.com/miniflux/v2) | Atom/RSS/JSON Feed, OPML, PostgreSQL search, sanitized reading and tracking protections | Small reliable feed foundation, feed portability, privacy-aware reader | Integration candidate; Apache-2.0 shown; evaluate actual API fit |
| R03 [NewsBlur](https://github.com/samuelclay/NewsBlur) | Trainable like/dislike filters for author/tag/title/feed and feed discovery | Editable recommendation rules and inspectable feedback | Design reference; MIT shown; full product architecture not assumed suitable |
| R04 [Folo](https://github.com/RSSNext/Folo) | Timeline summaries, scoped article/feed Q&A, tasks, integrations, source formats | Scoped cited questions, daily digest, optional export actions | UX reference; AGPL-3.0 shown; hosted functionality not assumed available in every self-hosted configuration |
| R05 [RSSHub](https://github.com/DIYgod/RSSHub) | Route-based feed aggregation for many web sources | Approved route catalog and connector capability checks | Optional ingestion boundary; AGPL-3.0 shown; route existence does not establish content rights |
| R06 [SmartReader](https://github.com/KonH/SmartReader) | Fast keyword scoring followed by LLM ranking, feedback loop, configurable stages | Cheap candidate filtering before expensive enrichment | Small implementation reference; MIT shown; no production-readiness assertion |

### 21.3 Research and reading references

| ID / repository | Documented capability | Improvement to InterestAtlas | Reuse stance / license observation |
|---|---|---|---|
| R07 [arxiv-sanity-lite](https://github.com/karpathy/arxiv-sanity-lite) | Tag-based paper recommendations using abstract features; daily recommendation emails | Seed-paper onboarding and an inexpensive recommendation baseline | Algorithm reference; MIT shown; commit-history fetch failed, so current maintenance freshness not established |
| R08 [PaperFlow](https://github.com/OpenRaiser/PaperFlow) | Daily paper recommendations, reading reports, feedback, short/long interest drift, local research graph | Separate persistent interests from current research missions | Close conceptual reference; MIT shown; longitudinal quality not independently evaluated |
| R09 [Paperlib](https://github.com/Future-Scholars/paperlib) | Metadata scrapers, smart filters, notes, research RSS, reference export, extensions | Provider reconciliation, research collections, citation workflows | Workflow/reference integration; GPL-3.0 shown; metadata coverage still needs source-specific testing |
| R10 [Zotero](https://github.com/zotero/zotero) | Collect, organize, annotate, cite, and share research sources | Citation portability and citation-linked notes rather than isolated saved PDFs | Interoperability reference; main COPYING declares AGPLv3 and third-party notices |
| R11 [KOReader](https://github.com/koreader/koreader) | Multi-format document reading including Android and e-ink-oriented layouts | Reader accessibility, document navigation, low-distraction long-form use | UX reference; AGPL-3.0 shown; do not import its entire engine without a separate decision |

### 21.4 Extraction and retrieval references

| ID / repository | Documented capability | Improvement to InterestAtlas | Reuse stance / license observation |
|---|---|---|---|
| R12 [Trafilatura](https://github.com/adbar/trafilatura) | Web text/metadata extraction, feed/sitemap discovery, queue processing | HTML extraction adapter with quality checks and provenance | Evaluate as a library; current Apache-2.0 declaration; README states pre-1.8.0 versions used GPLv3+ |
| R13 [Mozilla Readability](https://github.com/mozilla/readability) | Reader-view extraction from a DOM | Alternate HTML extraction and clean article view | Evaluate as a library; Apache-2.0 shown; extraction is not HTML sanitization |
| R14 [Docling](https://github.com/docling-project/docling) | Structured document conversion, PDF layout/tables, OCR, local processing | Evidence anchored to page structures instead of flattened PDF text | Evaluate document pipeline; MIT shown for code; model/dependency terms separately checked |
| R15 [GROBID](https://github.com/grobidOrg/grobid) | Scholarly metadata, references, full-text structure, coordinates, service API | Resolve bibliographies and link evidence back to PDF regions | Evaluate research parsing service; Apache-2.0 shown |
| R16 [ColBERT](https://github.com/stanford-futuredata/ColBERT) | Token-level late-interaction passage retrieval | Optional higher-quality evidence retrieval benchmark | Later-stage candidate; MIT shown; benchmark indexing and hardware cost before adoption |
| R17 [Microsoft GraphRAG](https://github.com/microsoft/graphrag) | LLM-based structured extraction and graph-oriented retrieval pipeline | Explore questions across connected corpus segments | Optional experiment; MIT shown; README explicitly warns about indexing costs and describes a demonstration rather than an officially supported offering |
| R18 [LightRAG](https://github.com/HKUDS/LightRAG) | Graph-enhanced retrieval, document processing, operational graph/index tooling | Evaluate incremental knowledge indexing and document-contribution tracking | Alternative experiment; MIT shown; deletion/re-embedding correctness must be tested |

### 21.5 Ranking, verification, and evaluation references

| ID / repository | Documented capability | Improvement to InterestAtlas | Reuse stance / license observation |
|---|---|---|---|
| R19 [Gorse](https://github.com/gorse-io/gorse) | Recommendation engine with item/user/interaction inputs, multiple candidate sources, REST APIs | Optional feedback-aware candidate generation behind our policy filter | Evaluate once enough data exists; Apache-2.0 shown; our explicit-interest and evidence rules stay outside it |
| R20 [RecBole](https://github.com/RUCAIBox/RecBole) | Unified recommendation research framework and evaluation protocols | Compare ranking baselines using time-respecting experiments | Offline experiment tool; MIT shown; not a turnkey content ingestion backend |
| R21 [CIMPLE knowledge-base](https://github.com/CIMPLE-project/knowledge-base) | Knowledge graph connecting ClaimReview/fact-checking records and misinformation datasets | Preserve external review provenance and distinguish a review from its underlying evidence | Schema/data-source reference; reuse permissions not established in this review; domain coverage cannot be assumed for new AI claims |
| R22 [NewsTrace](https://github.com/teenatom17/NewsTrace) | RSS-to-story pipeline with entities, clustering, timelines, and contradiction analysis | Story-first reading and explicit update-versus-contradiction checks | Prototype reference; license not established; README lists improved claim comparison and update distinction as future work |
| R23 [investigator](https://github.com/dsivov/investigator) | Cross-story entities, graph bridges, quoted-source dossiers | Explain why two stories connect without implying unsupported causation | Prototype reference; MIT shown; graph leads remain hypotheses until supported |
| R24 [Ragas](https://github.com/vibrantlabsai/ragas) | Evaluation metrics, test-data generation, and feedback workflows for LLM applications | Repeatable citation/summary evaluation and regression datasets | Evaluate test harness; Apache-2.0 shown; automated judges are not independent ground truth |
| R25 [ARES](https://github.com/stanford-futuredata/ARES) | RAG evaluation for context relevance, faithfulness, and answer relevance | Separate retrieval failures from generation failures | Research evaluation reference; Apache-2.0 shown; validate judge/domain fit |

### 21.6 Maintenance and evidence limits

Release pages were inspected for [Miniflux](https://github.com/miniflux/v2/releases), [Folo](https://github.com/RSSNext/Folo/releases), and [Docling](https://github.com/docling-project/docling/releases); their visible release histories support investigating them as maintained candidates. This limited check does not establish the security, support quality, or maintenance status of all 25 projects. No star-count ranking is used.

Before selecting any dependency, record a pinned commit/release, release date, recent maintenance activity, issue responsiveness, security policy/advisories, test results, license details, required runtime, resource costs, and exit strategy. Do not infer quality from README claims or popularity. The survey is intended to improve the design, not choose a package solely by appearance.

## 22. Revised product direction

Position the app as an evidence-aware personal research and news workspace. It should answer five recurring questions:
1. What matters to my interests today?
2. What is genuinely new since I last checked?
3. What evidence supports this, and what is still uncertain?
4. How does this connect to a paper or story I previously read?
5. What should I save or read next for my current goal?

The proposed differentiator is the combination of editable interest layers, traceable evidence, and personal reading continuity. This is a design conclusion from the survey, not a claim that no competing app has it. No reviewed README establishes the entire proposed product as already implemented and validated.

Separate three recommendation contexts:
- Following: a predictable chronological stream from sources/entities the user explicitly follows.
- For You: an explainable ranked stream controlled by interest layers and diversity rules.
- Mission: a temporary goal such as “understand low-latency local speech models,” with its own collection, sources, expiry, and digest.

Switching contexts must not silently rewrite the user's persistent core interests. A global mute still applies unless the user explicitly overrides it within a mission.

## 23. Eighteen concrete v2 enhancements

The following requirements are our proposed synthesis. Repository IDs identify inspirations; they do not imply those projects implement every safeguard specified here.

### V2-01 — Portable source inbox [R01, R02, R05]

Add URL/feed discovery with a preview, RSS/Atom/JSON Feed support, OPML import/export, folders, and feed-health indicators. Import is staged: validate, deduplicate subscriptions, show source permissions/status, then apply the user's selection. WebSub is an optional freshness optimization with polling fallback. Never auto-subscribe to an entire discovered domain.

Acceptance: import/export preserves supported subscription URLs and folders; malformed OPML cannot trigger unbounded fetches or external-entity access; duplicate subscriptions do not duplicate articles.

### V2-02 — Temporary research missions [R08; original synthesis]

Let users create a time-bounded goal with a question, scope, excluded subjects, preferred content types, reading budget, and end date. A mission can be seeded with selected papers or manually entered interests. It can influence a mission feed without changing the global profile. Offer an explicit “make this a lasting interest” action.

Acceptance: a one-week AI audio mission expires without promoting AI audio to core; its saved collection survives expiry; deletion removes mission-derived private indexes.

### V2-03 — Seed-based onboarding [R07, R08]

Accept a few user-selected papers, article URLs, authors, or pasted topic descriptions. Suggest editable topic mappings and nearby interests. Preview any resulting subscriptions or profile changes. Do not scrape a user's accounts or assume personal interest from an uploaded work they merely need to review.

Acceptance: a paper spanning hardware and NLP produces separately editable suggestions; rejecting a suggestion leaves no persistent inferred interest from that seed.

### V2-04 — Recommendation controls and two interest timescales [R03, R08, R19]

Represent explicit persistent preferences, slow-changing inferred preferences, and short-lived session/mission preferences separately. Provide author/entity/keyword/source rules, rule previews, snooze duration, and a readable explanation of the dominant ranking factors. Include “not now,” “wrong topic,” “already know,” and “poor evidence” feedback; these must affect different signals.

Acceptance: dismissing a duplicate changes novelty handling rather than reducing interest in the entire subject; reading in a mission does not silently override pinned global layers.

### V2-05 — Daily time budget and finite reading queue [R04, R08; original synthesis]

Offer an optional 5/15/30-minute or custom briefing budget. Allocate estimated time between news and papers; distinguish a two-minute summary from the full paper's unknown or estimated duration. Build a finite queue with “caught up” rather than endless compulsory scrolling. A correction can appear outside the budget with a clear reason.

Acceptance: a 15-minute briefing labels its estimates and distinguishes summary time from full reading; insufficient content yields a smaller briefing rather than irrelevant padding.

### V2-06 — Saved searches and entity watchlists [R03, R09]

Save queries over topics, authors, organizations, models, datasets, techniques, publication status, and evidence state. Display structured filters after natural-language query interpretation so users can correct them. Alert on meaningful matches and changes, not every duplicate mention. Maintain entity aliases and disambiguation.

Acceptance: a watch for a particular model version does not accidentally match a different product with the same acronym; repeated syndication produces one story alert.

### V2-07 — Story cards and meaningful-change briefings [R04, R22]

Group same-event coverage into one expandable card: what happened, original source, independent coverage, new evidence, disagreements, and timeline. Add “Since you last read this” using an explicit last-seen story version. Count content as new only when it adds meaningful facts, evidence, corrections, or a material status change. Merely changing words is not a new development.

Acceptance: five copies of a launch post become one card; an independent benchmark later adds a dated update without deleting earlier claims or implying that a user read every source.

### V2-08 — Evidence comparison workspace [R21, R22, R23]

Add a claim table with statement, source, cited passage, date, evaluation conditions, evidence origin, and assessment status. Distinguish “the company announced X,” “the paper reports X,” and “an independent test measured X.” A change between model versions or datasets is not automatically a contradiction. A source's assessment can be shown as attributed external review, not silently converted into our own verdict.

Acceptance: two latency figures measured on different hardware are shown as non-comparable until their conditions are aligned; an accessible supporting passage is required for a strong label.

### V2-09 — Paper-to-code-to-news development chains [R09, R10, R23; original synthesis]

Connect a research work to documented manuscript versions, verified official implementation links, datasets, releases, independent tests, and news coverage. Store relationship provenance and whether a code link is author-provided, independently verified, or only a candidate. A repository does not prove reproducibility, and a citation does not prove endorsement.

Acceptance: an author-linked implementation and an unrelated similarly named repository remain distinguishable; a new release cannot be attributed to the paper without supporting evidence.

### V2-10 — Research comparison matrix [R09, R15; original synthesis]

For selected papers, compare problem, method, dataset, baseline, hardware, version, author-reported results, limitations, publication status, code/data, and independent reproduction when known. Every populated cell has source provenance; missing data stays unknown. Disable automatic winner labels when conditions are incompatible.

Acceptance: papers using different evaluation data are labeled non-comparable in the relevant row; citation count is never used as proof of correctness.

### V2-11 — Precise evidence and annotation anchors [R10, R11, R14, R15]

Store page number, document version/hash, paragraph/section identifiers, selected quote with surrounding text, and PDF coordinates where available. A citation opens the exact supporting location. Preserve original page images where permitted for table/numeric checks. On version change, attempt reattachment but mark uncertain anchors for review rather than silently moving them.

Acceptance: an updated paper cannot make an old highlight point to an unrelated sentence; offline evidence links work for locally available versions.

### V2-12 — Scoped evidence-backed Q&A [R04, R17, R18]

Allow questions over one article, a story, a selected collection, or a mission. Show scope and content-access limitations before answering. Cite supporting passages and separate supported synthesis from hypotheses. Offer “not enough evidence.” Personal/private collections must be excluded from other users' retrieval and public caches.

Acceptance: asking about inaccessible full text does not produce a guessed answer; an answer grounded only in abstracts says so; cross-user retrieval tests return no private records.

### V2-13 — Small related-connections view [R08, R17, R23]

Provide an optional focused graph around the current item, with readable relationship types, source evidence, and a timeline/list equivalent for accessibility. Bound graph expansion by relevance, permissions, and cost. Label “possible research lead” separately from “documented relationship.” Do not place an enormous graph on the default home screen.

Acceptance: a shared organization produces “mentions the same organization,” not a causal edge; clicking any asserted connection exposes its evidence.

### V2-14 — Reading outputs and interoperability [R04, R09, R10]

Export selected bibliographic metadata as BibTeX/RIS where supported; export user notes/highlights as Markdown/JSON with source links, versions, and anchors. Add the Android share-target workflow for saving a public URL. Later integrations can send selected items to a user-chosen external library only after connection and explicit action. Do not bulk-copy full text without permission.

Acceptance: export/import round-trip preserves supported metadata and user annotations; missing fields remain absent rather than fabricated; sending notes externally is off by default.

### V2-15 — Extraction routing and quality control [R12, R13, R14, R15]

Route feed content/HTML through a suitable text extractor; route scholarly PDFs through research metadata/reference extraction; use layout-aware parsing where tables/formulas need it. Do not run every parser on every document. Keep original extraction outputs and the chosen canonical representation version. Detect missing sections, boilerplate, suspicious reading order, and metadata disagreement before summarization.

Acceptance: a three-column PDF with a results table retains usable evidence anchors; disagreement about title/author is recorded instead of overwritten; expensive fallback has a strict resource budget.

### V2-16 — Economical progressive enrichment [R06, R16, R17]

First apply permitted access, metadata checks, duplicate detection, explicit interest matches, and inexpensive retrieval. Enrich a bounded candidate set; generate deeper summaries/comparisons on demand or for high-priority briefings. Share public document processing across users while keeping ranking/history/private assets scoped. Expose pending states and cancel obsolete jobs when a mission expires or consent changes.

Acceptance: one public document version is parsed once for many subscribers where policy permits; a cloud-model outage still permits chronological feed, search, saved reading, and manual organization.

### V2-17 — Three independent evaluation tracks [R20, R24, R25]

Evaluate (a) recommendation usefulness/diversity, (b) retrieval recall and correct evidence location, and (c) summary/answer support and uncertainty handling. Use held-out chronological data, human-reviewed labels, real source failures, and explicit topic-shift scenarios. Compare rules-only, text-similarity, and optional learned rankers. Account for exposure bias: unseen content is not automatically negative feedback.

Acceptance: an improvement in clicks cannot excuse worse source diversity or unsupported claims; the same held-out test set is not used to tune and then claim validation success. Automated judges are calibrated against human reviews.

### V2-18 — Dependency-aware updates and deletion [R18; original synthesis]

Maintain contribution/dependency records from article version to extracted chunks, claims, graph edges, summaries, comparison cells, search indexes, and digests. On correction, replacement, rights restriction, or deletion, invalidate the affected contributions while preserving other independent sources. User-history deletion removes user-specific derivations without deleting shared public content owned by the service.

Acceptance: removing one source withdraws its sole-supported edge but preserves an edge also supported by another permitted source, with evidence recomputed; embedding-model migration cannot mix incompatible vector spaces or resurrect deleted private data.

## 24. Design example: one evolving AI story

This is a fictional scenario for requirements testing, not a report of an actual release.

1. A lab publishes a preprint about an audio-language model. The app displays author-reported findings, preprint status, abstract/full-text access, and source links.
2. The user reads only its summary. History records summary-read, not full-paper completion. A permitted PDF is downloaded separately.
3. Several blogs repeat the announcement. The app offers one story card and records their common evidence origin.
4. An official repository is linked from the paper page. The development chain gains an author-provided implementation edge; reproducibility remains unknown.
5. An independent test reports different latency on other hardware. The comparison shows setup differences and does not instantly call the claims contradictory.
6. The user opens a one-week “local speech inference” mission. Audio and hardware gain mission relevance, while the global LLM core remains pinned.
7. A paper revision corrects a results table. The app links the new version, shows the correction to exposed readers, invalidates affected summaries, and preserves version-specific notes.
8. Tomorrow's briefing says what changed since the user's recorded story exposure. If they only opened the summary, it says “since you viewed the summary.”

## 25. Revised architecture and build-versus-integrate decisions

### 25.1 Own the distinctive product behavior

Build and control the interest graph, layer policies, mission isolation, reading semantics, evidence states, source-independence records, story versioning, and correction/deletion propagation. These establish the application's promise and cannot be delegated blindly to a generic ranker or graph package.

Use evaluated libraries/adapters for commodity work: feed parsing, HTML extraction, document structure, bibliographic import/export, notifications, and evaluation. A complete reader product may be a reference or optional interoperable service; adopting one does not supply the evidence/research features automatically.

### 25.2 Staged integration choices

| Need | Initial choice | Later evaluation |
|---|---|---|
| Feed processing | Approved feed connectors with bounded polling and portable subscriptions | Miniflux integration or permitted RSSHub routes if they reduce measured work |
| HTML extraction | Evaluate Trafilatura against representative pages; use sanitization separately | Readability fallback where it measurably helps |
| Research PDFs | One document adapter; metadata-first and lazy full processing | GROBID for scholarly structure; Docling for layout/table needs; select by workload |
| Recommendation | Explicit rules plus topic/text matching and diversity re-ranking | Gorse candidate generation if interaction data justifies it; RecBole for experiments |
| Search/evidence retrieval | Relational metadata/full-text search plus an optional evaluated embedding index | ColBERT only after measurable evidence-retrieval gains justify resources |
| Story connections | Typed, evidence-linked relations in the primary data model | GraphRAG or LightRAG for a bounded experiment, not both by default |
| Evaluation | Curated fixtures, human-reviewed samples, deterministic checks | Ragas and/or ARES where reproducible and domain-appropriate |

This table is an engineering proposal, not a tested dependency selection. Validate runtime versions, connectors, actual API semantics, and license compatibility at the implementation milestone. Do not install 25 systems because they appear in this survey.

### 25.3 Additional records and invariants

Add Mission, MissionScope, MissionTopicPreference, SeedItem, SavedQuery, EntityWatch, RecommendationRule, RankingExplanation, ExposureRecord, ReadingQueue, ReadingBudget, StoryVersion, StoryChange, EvidenceOrigin, ReviewAttribution, DocumentAnchor, ExtractionArtifact, ResearchComparison, ComparisonCellEvidence, ArtifactDependency, ProcessingBudget, ExternalIntegrationConsent, and DependencyAssessment.

Invariants:
- Mission changes never silently mutate pinned global preferences.
- Evidence origin is independent of article URL count.
- Every nontrivial comparison cell and asserted graph edge retains supporting versioned evidence.
- A story change links an old version, a new version, and a meaningful-change reason.
- Private retrieval authorization happens before retrieval/ranking and again at delivery where appropriate; embeddings are not an authorization boundary.
- Deletion and correction jobs are idempotent, observable, and cover every derived store.
- A new embedding/model/extractor version does not silently reinterpret existing anchors or mix incompatible indexes.

## 26. Revised release priorities

This section replaces the priority ordering in Section 18 while preserving its transparency requirements.

| Milestone | Scope | Exit condition |
|---|---|---|
| Foundation | Approve sources, confirm rights, choose client/backend, inspect dependency candidates, establish evaluation corpus and budgets | Architecture decisions, connector proofs, UX flow, and baseline data contracts are reviewable |
| MVP reading product | Feed discovery/OPML; manual layers; Following/For You; source health; deduplication; basic story grouping; research metadata/downloads; saved searches; share-to-save; history; daily finite briefing; honest evidence/access states | Complete reading loop works offline for permitted saved content; privacy and retries pass; core acceptance scenarios pass |
| Evidence and continuity | Cited summaries; precise anchors; story changes; evidence comparison; paper versions and documented code links; corrections; bounded cited Q&A | Human-reviewed evaluation gates pass; updates invalidate derived claims/summaries; false merges are reversible |
| Adaptive research workspace | Missions; two-timescale inference; seed-based onboarding; research comparisons; optional focused graph; richer export/sync | Explicit choices remain dominant; mission/global isolation and chronological evaluations pass |
| Expansion | Additional approved routes, optional multimedia transcripts, newsletter intake, external library integrations, learned rankers | Each integration has access/privacy approval, measurable value, and operating budgets |

Keep multimedia and newsletter ingestion out of the default MVP. AI-audio and AI-video are interest topics even when their coverage is a text article; they do not require ingesting actual audio/video files. Add transcript processing only as a later content-format capability, with rights and quality labels.

Optional educational features such as glossaries, prerequisite reading paths, or spaced review can be tested later. Do not let them delay a reliable research/news reading loop or imply that reading history measures mastery.

## 27. Expanded agent instructions for v2

Use this extension with the full Section 19 master prompt, not as a replacement for its safety, privacy, evidence, or completion requirements.

```text
Implement the GitHub-informed v2 product requirements V2-01 through V2-18 in the milestone order in Section 26. Start by converting the baseline and v2 requirements into one traceable backlog. Prevent v2 features from weakening the baseline's transparency, source policy, consent, offline, or deletion requirements.

Repository survey rules:
1. The 25 projects are references, not mandatory dependencies and not proof that the desired capabilities work.
2. For every proposed dependency, inspect the actual selected revision, license files and notices, maintenance/security signals, setup/runtime, tests, resource costs, integration boundary, and replacement path. Record evidence and unresolved items. Do not copy code from projects with unestablished permission.
3. Separate design inspiration, protocol/API interoperability, library adoption, service integration, and forking. Explain the selected mode for each dependency. Do not assume a network boundary removes license obligations or that a code license authorizes publisher-content reuse.
4. Compare a simple baseline before adopting learned ranking, token-level retrieval, or a graph retrieval framework. Do not adopt GraphRAG and LightRAG together without a demonstrated need.

Product refinements:
- Deliver Following, For You, and isolated Mission contexts with explicit state and predictable controls.
- Use stable explicit preferences plus separate long-window and short-window inferred signals. Store feedback reasons rather than treating every dismissal as a negative topic preference.
- Prefer a finite, time-budgeted daily queue and story changes to repeated headlines. Make summary/full-text exposure differences visible.
- Add saved structured searches and entity watchlists with alias disambiguation and deduplicated alerts.
- Treat paper, code repository, dataset, release, benchmark, and coverage as separate records with evidence-linked relationships.
- Anchor citations and annotations to exact document versions and locations. Compare research only under compatible evaluation conditions.
- Keep Q&A scope explicit, citations inspectable, and insufficient-evidence responses available.
- Maintain artifact dependencies for correction, retraction, rights changes, history deletion, private-data deletion, and embedding migration.

Engineering milestones:
A. Show one working approved source, one real paper metadata record, a lawful download path if available, manual layer ranking, a saved query, and a daily briefing. Record failures and unavailable data honestly.
B. Add a human-reviewed fixture corpus with independent/syndicated sources, benchmark setup changes, paper revisions, missing full text, malformed extraction, ambiguous identities, false story merges, and injected instructions.
C. Evaluate extraction, ranking, retrieval, and generation separately. Use chronological splits and report observed metrics without claiming universal factual accuracy.
D. Enable more ambitious automated features only when their quality, privacy, deletion, and cost gates pass. Otherwise preserve a clearly labeled manual or limited fallback.

For every completed feature, link requirement IDs to UI/API/schema behavior and meaningful test evidence. Do not report a repository capability as a feature of our app until it is implemented and validated here. Keep all sample content clearly labeled. Include connector health, processing costs, correction queue, and derived-data invalidation in operational documentation.
```
