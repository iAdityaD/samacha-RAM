package com.samacharam.core.db

/**
 * Structured schema. Design notes:
 * - Integer keys and lookup tables keep repeated values (sources, topics) out of every row.
 * - Long text columns are BLOBs in the [com.samacharam.core.TextCodec] format.
 * - article_fts is contentless (no second copy of the text) with detail=column (no token positions).
 * - seen_url keeps a 64-bit fingerprint of pruned items so they are not re-added as new.
 */
object Schema {
    const val VERSION = 1

    /** Must run on every new connection, before any other statement. */
    val connectionPragmas = listOf(
        "PRAGMA foreign_keys=ON",
        "PRAGMA journal_mode=WAL",
        "PRAGMA synchronous=NORMAL",
    )

    /** auto_vacuum only takes effect when set before the first table is created. */
    const val CREATE_PRAGMA = "PRAGMA auto_vacuum=INCREMENTAL"

    val v1 = listOf(
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
        "CREATE TABLE topic(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, layer INTEGER NOT NULL DEFAULT 1)",
        """CREATE TABLE source(
            id INTEGER PRIMARY KEY,
            key TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,
            url TEXT NOT NULL UNIQUE,
            type INTEGER NOT NULL,
            topic_id INTEGER REFERENCES topic(id),
            enabled INTEGER NOT NULL DEFAULT 1,
            etag TEXT,
            last_modified TEXT,
            failures INTEGER NOT NULL DEFAULT 0,
            next_check INTEGER,
            last_success INTEGER
        )""",
        """CREATE TABLE article(
            id INTEGER PRIMARY KEY,
            url_hash INTEGER NOT NULL UNIQUE,
            url TEXT NOT NULL,
            source_id INTEGER REFERENCES source(id),
            title TEXT NOT NULL,
            authors TEXT,
            published INTEGER,
            first_seen INTEGER NOT NULL,
            type INTEGER NOT NULL,
            minutes INTEGER NOT NULL,
            version INTEGER NOT NULL,
            title_hash INTEGER NOT NULL,
            excerpt BLOB,
            flags INTEGER NOT NULL DEFAULT 0
        )""",
        "CREATE INDEX article_published ON article(published)",
        "CREATE INDEX article_first_seen ON article(first_seen)",
        """CREATE TABLE article_topic(
            article_id INTEGER NOT NULL REFERENCES article(id) ON DELETE CASCADE,
            topic_id INTEGER NOT NULL REFERENCES topic(id),
            PRIMARY KEY(article_id, topic_id)
        ) WITHOUT ROWID""",
        "CREATE TABLE saved(article_id INTEGER PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE, saved_at INTEGER NOT NULL, note BLOB)",
        """CREATE TABLE highlight(
            id INTEGER PRIMARY KEY,
            article_id INTEGER NOT NULL REFERENCES article(id) ON DELETE CASCADE,
            quote TEXT NOT NULL,
            note BLOB,
            doc_version TEXT,
            anchor TEXT,
            created INTEGER NOT NULL
        )""",
        "CREATE INDEX highlight_article ON highlight(article_id)",
        """CREATE TABLE history(
            id INTEGER PRIMARY KEY,
            article_id INTEGER NOT NULL REFERENCES article(id) ON DELETE CASCADE,
            kind INTEGER NOT NULL,
            at INTEGER NOT NULL,
            version INTEGER
        )""",
        "CREATE INDEX history_article ON history(article_id)",
        "CREATE TABLE seen_url(url_hash INTEGER PRIMARY KEY, last_seen INTEGER NOT NULL)",
        """CREATE VIRTUAL TABLE article_fts USING fts5(
            title, body,
            content='', contentless_delete=1, detail=column,
            tokenize='unicode61 remove_diacritics 2'
        )""",
    )

    /** Ordered migrations: index i upgrades version i+1 to i+2. New databases are created at v1 and migrated forward. */
    val migrations: List<List<String>> = listOf(
        // v2: feed refresh state per source.
        listOf(
            "ALTER TABLE source ADD COLUMN last_checked INTEGER",
            "ALTER TABLE source ADD COLUMN last_error TEXT",
            "ALTER TABLE source ADD COLUMN interval_minutes INTEGER NOT NULL DEFAULT 60",
            "ALTER TABLE source ADD COLUMN item_count INTEGER NOT NULL DEFAULT 0",
        ),
        // v3: explicit feedback and temporary missions.
        listOf(
            """CREATE TABLE feedback(
                id INTEGER PRIMARY KEY,
                article_id INTEGER NOT NULL REFERENCES article(id) ON DELETE CASCADE,
                reason TEXT NOT NULL,
                at INTEGER NOT NULL
            )""",
            "CREATE INDEX feedback_article ON feedback(article_id)",
            "CREATE TABLE mission(id INTEGER PRIMARY KEY, title TEXT NOT NULL, topics TEXT NOT NULL, expires TEXT NOT NULL, created INTEGER NOT NULL)",
        ),
    )

    val LATEST: Int get() = VERSION + migrations.size
}

object ArticleFlags {
    const val STARTER = 1L
    const val CHANGED = 2L
    const val EXCERPT_PRUNED = 4L
}
