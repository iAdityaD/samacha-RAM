package com.samacharam.core

import com.samacharam.core.db.SqlDriver
import com.samacharam.core.db.SqlRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Test driver over SQLite JDBC, which bundles a modern SQLite with FTS5, like androidx sqlite-bundled. */
class JdbcDriver(val file: File) : SqlDriver, AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private var depth = 0

    private fun prepare(sql: String, args: List<Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { i, value ->
            when (value) {
                null -> statement.setObject(i + 1, null)
                is Long -> statement.setLong(i + 1, value)
                is Int -> statement.setLong(i + 1, value.toLong())
                is Double -> statement.setDouble(i + 1, value)
                is Boolean -> statement.setLong(i + 1, if (value) 1 else 0)
                is String -> statement.setString(i + 1, value)
                is ByteArray -> statement.setBytes(i + 1, value)
                else -> error("Unsupported SQL argument ${value::class}")
            }
        }
        return statement
    }

    override fun execute(sql: String, args: List<Any?>) {
        prepare(sql, args).use { statement ->
            if (statement.execute()) statement.resultSet.use { rs -> while (rs.next()) Unit }
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        prepare(sql, args).use { statement ->
            statement.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) { depth++; try { return block() } finally { depth-- } }
        connection.autoCommit = false
        depth = 1
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            depth = 0
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    private class Row(val rs: ResultSet) : SqlRow {
        override fun long(index: Int): Long? = rs.getLong(index + 1).takeUnless { rs.wasNull() }
        override fun double(index: Int): Double? = rs.getDouble(index + 1).takeUnless { rs.wasNull() }
        override fun string(index: Int): String? = rs.getString(index + 1)
        override fun bytes(index: Int): ByteArray? = rs.getBytes(index + 1)
    }
}
