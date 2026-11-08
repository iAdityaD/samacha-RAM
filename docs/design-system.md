# samacha·RAM design system

*samachar* (समाचार) means news. The product is a private reading room for research and technical news: it should feel like a well-edited journal, not a dashboard.

## 1. Product understanding

**Audience.** Engineers, researchers, and learners who follow fast-moving technical fields (LLMs, audio, hardware) and read on their phone in short sessions: morning catch-up, commute, between meetings.

**Core workflows, by frequency**

| # | Workflow | Frequency | Primary action |
|---|---|---|---|
| 1 | Catch up on today's briefing | Daily | Open the next unread item |
| 2 | Triage the feed | Daily | Scan headline → open, save, or skip |
| 3 | Read an item and decide | Daily | Read at publisher / save / note |
| 4 | Revisit saved items and notes | Weekly | Find, open, edit note |
| 5 | Tune interests and missions | Occasional | Change a topic's layer |
| 6 | Configure sources, models, sync | Rare | Add / test / toggle |

**Information hierarchy of a story:** headline → source and freshness → one-line dek → reading cost and why it is shown. Everything else (type, layer, actions) is secondary and appears on demand.

**Audit of the previous UI (from emulator screenshots, Pixel 6)**

| Problem | Evidence | Consequence |
|---|---|---|
| Wasted first screen | Large app bars use ~22% of the height; the first story starts at 77% of the screen on Today | Users scroll before seeing any news |
| Card-on-card | A mint briefing card, then elevated cards for every story | Heavy, generic, low density |
| Tag soup | BLOG · LLMS · 1 MIN · CORE pills on every row | Competes with headlines |
| Controls everywhere | Bookmark + overflow + pills on every row | Visual noise, accidental taps |
| Rare actions in prime space | "New mission" chip and a segmented control above the feed | Pushes content down |
| Dead UI | Summarize and Ask shown disabled when no excerpt exists | Confusing |
| Noisy feedback | "Phone refresh: 0 new…" snackbar follows users across tabs | Messages without news value |
| No identity | Default Material green, sans everywhere except titles | Looks like a template |

## 2. Visual identity: "Ink and paper"

**Philosophy.** Editorial calm. Typography and whitespace carry the design; color is rare and meaningful. Content sits on the page, separated by hairlines rather than boxes. One brand color for action, one signal color for what's new.

### Color tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | #F7F4EE | #12110F | App background |
| `paperRaised` | #FFFDF9 | #1B1A17 | Sheets, dialogs, input fills |
| `paperSunk` | #EFEBE3 | #22201C | Grouped settings, search field |
| `ink` | #1C1B18 | #ECE7DE | Primary text, headlines |
| `inkMuted` | #5E5A52 | #A8A296 | Secondary text, metadata |
| `inkFaint` | #8D877C | #7A7468 | Tertiary text, disabled |
| `rule` | #E2DDD3 | #2E2B26 | Hairline dividers |
| `evergreen` | #1E5B45 | #8FCBAE | Brand and primary actions, selection, links |
| `evergreenSoft` | #DCEBE2 | #1F3A2F | Selected backgrounds, Core marker fill |
| `saffron` | #C97B12 | #E8A94A | Signal only: unread/new marker, briefing progress |
| `danger` | #A8321F | #F0937F | Destructive actions, errors |

Measured contrast (WCAG 2.x), light / dark: ink on paper 15.7 / 15.3; inkMuted on paper 6.3 / 7.4; inkMuted on paperSunk 5.8 / 6.4; evergreen on paper 7.3 / 10.2; text on evergreen 8.0 / 8.3; evergreen on evergreenSoft 6.5 / 6.6; danger 6.1 / 8.3. All text pairs pass AA; ink passes AAA.
`inkFaint` measures 3.3:1 in light mode, so it is used only for disabled states and decorative glyphs (exempt), never for readable text. Saffron (3.0:1 in light mode) is never used for text; it appears only as a non-text marker next to a text equivalent.

Optional "wallpaper colors" (Material You) replace evergreen and its soft tint only; paper and ink stay, so the identity holds.

### Typography

System families, so nothing is downloaded: **Serif** (Noto Serif on Android) for reading, **Sans** (Roboto / device sans) for interface.

| Role | Family | Size / line | Weight | Use |
|---|---|---|---|---|
| Display | Serif | 32 / 38 | SemiBold | Screen mastheads |
| Headline | Serif | 24 / 30 | SemiBold | Article title |
| Story | Serif | 18 / 24 | Medium | Feed headlines |
| Title | Sans | 16 / 22 | SemiBold | Settings rows, dialogs |
| Body | Serif | 17 / 27 | Regular | Reading text (excerpts, notes) |
| UI | Sans | 15 / 21 | Regular | Controls, descriptions |
| Meta | Sans | 13 / 18 | Medium | Source, time, minutes |
| Kicker | Sans | 12 / 16 | SemiBold, +0.8 tracking, uppercase | Section labels, source names |

All sizes are in `sp` and scale with the system font size. Rows have no fixed heights.

### Spacing, shape, and elevation

- **Spacing scale (dp):** 4 · 8 · 12 · 16 · 20 · 24 · 32 · 48. Screen gutter 20. Row vertical padding 16.
- **Radius:** 0 for content rows (the page is the container); 12 for sheets, dialogs, and inputs; full pill for chips and buttons. Nothing else is rounded.
- **Elevation:** none on content. Only floating UI (bottom sheets, dialogs, menus, snackbar) elevates, at level 2–3. Separation comes from hairline `rule`s and whitespace.
- **Touch targets:** at least 48 × 48 dp; icon buttons 48 dp with 24 dp glyphs.

### Iconography

Material Symbols, **outlined**, 24 dp, `inkMuted` by default and `evergreen` when active (filled variant only for the active tab and saved state). No decorative icons beside headings; an icon must label an action or a state.

### Motion

| Token | Duration | Easing | Use |
|---|---|---|---|
| `quick` | 120 ms | standard | Press states, icon toggles |
| `standard` | 220 ms | emphasized decelerate | Screen enter, sheet open |
| `exit` | 160 ms | emphasized accelerate | Screen exit |

Screens enter with a 24 dp rise and fade; tabs crossfade. The bookmark scales 1 → 1.2 → 1 when toggled. List items animate placement when the feed changes. All motion respects the system "remove animations" setting.

### Illustration

None. Empty states use a single outlined glyph in a soft circle, a serif line, and one action. The product's character comes from type, not drawings.

## 3. Components

| Component | Spec |
|---|---|
| **Masthead** | Kicker line (date or section), serif display title, up to two 48 dp actions. Collapses to a compact bar on scroll. Replaces large app bars. |
| **StoryRow** | Kicker (source · time, with a 6 dp saffron dot if unread), serif headline (max 3 lines), muted dek (max 2), meta line (minutes · why shown). Single trailing bookmark. Tap opens; long-press opens the action sheet. Hairline below. Whole row is one accessible element with a merged label. |
| **ActionSheet** | Bottom sheet: title, then action rows (icon, label, optional description). Used for story actions, layer choice, and confirmations with choices. |
| **SectionLabel** | Kicker style in `inkMuted` with 20 dp gutter, 24 dp above and 8 dp below. |
| **TextTabs** | Underlined text tabs (Feed: For you / Following; Library: Saved / Highlights / History). Indicator in evergreen, 2 dp. |
| **Chip** | Pill, 32 dp high, outline `rule`; selected = evergreenSoft fill with evergreen text. Used only for filters. |
| **Buttons** | Primary: filled evergreen pill, 48 dp. Secondary: outlined pill. Tertiary: text. Destructive: text in danger, always confirmed. One primary per screen. |
| **Inputs** | Filled `paperSunk`, 12 dp radius, no underline; label above; inline error text in danger; supporting text in inkMuted. |
| **SettingRow** | Icon (optional) · title · description · trailing control; inside a `paperSunk` group with 12 dp radius. Switch rows toggle on whole-row tap. |
| **LayerMark** | A 10 dp shape before the layer name: Core = filled evergreen circle; Near = half-filled; Adjacent = outline; Discovery = dotted outline; Muted = struck. Readable without color. |
| **Skeleton** | Gray bars matching StoryRow geometry with a slow 1.2 s pulse; shown for first loads only. |
| **EmptyState** | Glyph in a 64 dp soft circle, serif title, one sentence, at most one action. |
| **Feedback** | Snackbars only for outcomes that change something the user can see, or for errors. "Nothing new" is shown inline in the masthead, not as a snackbar. |

## 4. Screens

| Screen | Purpose | Hierarchy | Primary / secondary |
|---|---|---|---|
| **Onboarding (3 steps)** | Start with a feed that matches the user | Promise → pick Core/Near topics → privacy choice | Continue / Skip |
| **Today** | Catch up in a fixed time | Masthead (date) → Briefing list (numbered, with time left and saffron progress) → For you / Following tabs → story rows | Open next unread / refresh (pull) |
| **Explore** | Find anything | Search field → filter chips → topic directory (with counts and layer marks) or results | Search / filter |
| **Library** | Return to what matters | Tabs: Saved (rows with note preview) · Highlights (quotes with a left rule) · History (grouped by day) | Open / export (overflow) |
| **Interests** | Shape the feed | Budget → layer sections with topic rows (LayerMark, tap to change via ActionSheet) → Missions | Change layer / add topic |
| **Profile** | Everything else | Identity line (storage mode) → grouped settings | Navigate |
| **Article** | Read and decide | Kicker → serif title → byline/date → access note → excerpt in reading type → sticky bottom bar (Read at publisher · Save · Ask) → notes → related | Read at publisher / Save / Ask (sheet) |
| **Settings pages** | Configure safely | Plain explanation → grouped rows → destructive actions last, confirmed | Per page |

Edge cases designed for: no excerpt (Ask hidden, a note explains), very long titles (3-line clamp in lists, full in article), unknown dates ("Date unknown"), no sources healthy (inline banner in Today), offline (inline banner, cached content still readable), a briefing already finished ("caught up").

## 5. Quality review checklist

Each release is reviewed from four perspectives against emulator screenshots (light and dark):

1. **First-time user:** can they tell what to do on every screen within 3 seconds? Is any jargon unexplained?
2. **Frequent user:** is the daily path (open → read next → save) at most two taps? Is anything repeated or slow?
3. **Product designer:** spacing on the 4 dp grid, one primary action per screen, consistent type roles, no orphan colors.
4. **Accessibility reviewer:** contrast ≥ 4.5:1 for text, 48 dp targets, content descriptions on icon buttons, merged semantics for rows, layout survives 200% font scale.

Findings and fixes are recorded in `docs/design-review.md`.
