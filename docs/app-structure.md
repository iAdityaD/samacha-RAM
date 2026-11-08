# App structure: one home for every feature

The Android app follows familiar social-app patterns: an icon-only bottom bar, story circles, posts with an action row, and a profile with counts and tabs. It still uses the "Ink and paper" tokens. The rule for this layout is that **each feature has exactly one home**. Other places link to it but never copy it.

## What the app must do

1. Collect stories from the owner's sources and rank them by the owner's topics.
2. Give a short daily briefing that ends, rather than an endless feed.
3. Let the owner read a story, keep it, and write notes.
4. Answer questions about a story or the library, with quotes checked against the source.
5. Let the owner shape what they see: topics by layer, sources, missions.
6. Keep data private, with optional encrypted sync, model choice, and export.

## Bottom bar (icon only, labelled for screen readers)

| Tab | Purpose | Owns |
|---|---|---|
| **Home** | What to read now | Briefing and topic story circles, the ranked "for you" feed, pull to refresh, the Activity entry (bell) |
| **Explore** | Everything, newest first | Search, type filters, topic shortcuts, the grid of all items |
| **Ask** | Questions, answered from your library | One chat. It works about one story (opened from that story) or across the library |
| **Library** | What you kept | Saved items, highlights, notes, reading history |
| **Profile** | Who you are as a reader | Counts, missions (circles), topics, sources, reading budget, Settings entry |

## Screens off the bar

| Screen | Opened from | Owns |
|---|---|---|
| Story player | Home circles, Profile mission circles | Swipe-through reading of a deck (briefing, topic or mission); tap to move, swipe up to read |
| Story | Any post, tile or row | The excerpt, your note on it, related items, "Read at publisher" |
| Topic | Explore shortcuts, Profile topic rows | Layer choice (the only place to change it), topic stats, the topic's items |
| Activity | Home bell | Briefing ready, sources failing, mission matches, sync |
| Missions | Profile "+" circle | Creating and ending missions |
| Add feed | Profile Sources tab | Adding a source |
| Settings | Profile menu | Briefing time and reminder, background refresh, backup and sync, AI models, storage, privacy, appearance, import and export, about |

## Removed duplicates

| Before | Now |
|---|---|
| Interests was a tab, and topics were also listed in Explore | Topics live in Profile. Explore only links to Topic pages. |
| Sources reachable from Explore, Profile and Today's banner | Sources live in the Profile Sources tab. Activity and the Home notice link there. |
| Markdown export in the Library menu and in Import and export | Export lives only in Settings → Import and export. |
| Ask as a sheet inside Story | Ask is one screen. Story's "Ask" opens it with that story attached. |
| Missions as Today filter pills and as a Missions page | Missions are Profile circles that open as a story deck. Missions is only for creating and ending them. |
| Refresh button on Today and on Sources | Pull to refresh on Home; per-source state lives on the Sources tab. |
