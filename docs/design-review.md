# Design review — "Ink and paper"

Review of the redesigned Android app against `docs/design-system.md`. It is based on the
emulator walk in `android-emulator.yml` (Pixel 6, API 34, light and dark), which
captures one screenshot per screen. Every finding below was fixed and checked again on the next run.

## 1. First-time user

| Finding | Fix |
|---|---|
| Today's primary action ("Start briefing") was below the fold, under three stories. | Moved a full-width primary button directly under the briefing label. Its label tracks state: "Start briefing", then "Continue · k of N", then "Briefing finished · read again". |
| Progress bar showed *planned minutes*, which reads as time remaining. | Progress now counts stories read ("2 of 5 read · 6 min"). |
| Source rows showed internal keys (`hf`) instead of names. | `Article.sourceName` added to the core query; rows use `sourceLabel()`. |
| Library empty state used a search glyph, which suggests the wrong action. | Bookmark glyph, plus copy that explains how items get saved. |
| Layer chooser segments were truncated on narrow screens. | Replaced with an action sheet that has one row per layer and a one-line description (`LAYER_HELP`). |

## 2. Frequent user

| Finding | Fix |
|---|---|
| Toolbar refresh also showed the pull-to-refresh spinner, so two indicators appeared at once. | Only the 2 dp busy line under the bar shows. A message appears only when there are new items or failures. |
| "0 min. ago" after a refresh. | `timeAgo` returns "just now" under 60 s. |
| Long-press actions differed between screens. | One `storyActions()` list feeds every action sheet (Today, Explore, Library, Article). |
| Article dates were raw ISO strings. | Localized with `DateFormat.MEDIUM`. |
| Floating button covered the last row. | Pages reserve 96 dp of bottom padding. |

## 3. Product designer

| Finding | Fix |
|---|---|
| Sync card showed body text in red: `errorContainer` resolved to the surface color. | New `dangerSoft` token; the card is neutral (`paperSunk`) when sync is off and `evergreenSoft` when it is on. Red is reserved for real errors. |
| Models and Sync pages still used stock Material styling (FilterChip, default fields, 16 dp gutter). | Converted to tokens: `Pill`, `formFieldColors()` with 12 dp radius, 20 dp gutter, evergreen FAB. The empty providers group is hidden in favor of one line of guidance. |
| Interest rows printed the layer twice (mark and text). | The row shows the mark and a short label; detail lives in the sheet. |
| Progress indicator drew a stop dot and a gap. | `gapSize = 0.dp`, `drawStopIndicator = {}`. |
| Mixed type: serif and sans used interchangeably. | Serif is for reading (display, headline, story, body); sans is for interface (title, ui, meta, kicker). Enforced through `TypeRole`. |

## 4. Accessibility

- **Contrast (measured):**
  - `ink` on `paper` is 15.7:1 and `inkMuted` is 6.3:1, both above 4.5:1 for text.
  - `inkFaint` is 3.25:1 in light mode, so it is used only for disabled or decorative marks, never for content.
  - `saffron` is never used for text; it marks unread dots and progress only.
- **Font scaling:** all sizes are in `sp`, and rows grow vertically instead of truncating headlines.
- **Touch targets:** every interactive element is at least 48 dp (`Space.touch`), including the save toggle and text tabs.
- **Semantics:** each story row's text is merged into one spoken label (headline, source, time, unread). Save is a separate labelled toggle, and long-press opens the same action sheet as the overflow. Layer marks have text equivalents, so color is never the only signal.
- **Dark mode:** a separate palette, not an inverted one. Accents are lightened (`evergreen #8FCBAE`, 10.2:1 on `#12110F`).
- **Motion:** transitions use 120–220 ms; there are no looping animations except the loading skeleton.

## Known limits

- The app uses the system serif and sans fonts, with no bundled custom font, to keep the APK small and work offline.
- Screenshots come from an emulator. A check on a physical device (OLED dark, 200% font scale, TalkBack) is still worth doing before release.
- The "Add model" button can overlap the last on-device file chips at the very bottom of the Models page. The bottom padding keeps them reachable.
