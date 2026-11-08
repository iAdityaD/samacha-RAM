package com.samacharam.core.db

/**
 * Minimal SQL boundary. Android implements it with the bundled SQLite driver;
 * JVM tests implement it with SQLite JDBC. Arguments may be Long, Int, Double, String, ByteArray, Boolean, or null.
 */
interface SqlDriver {
    /** Runs the statement to completion, stepping through (and discarding) any result rows. */
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
}

interface SqlRow {
    fun long(index: Int): Long?
    fun double(index: Int): Double?
    fun string(index: Int): String?
    fun bytes(index: Int): ByteArray?
}

fun SqlDriver.queryLong(sql: String, args: List<Any?> = emptyList()): Long =
    query(sql, args) { it.long(0) ?: 0L }.firstOrNull() ?: 0L
