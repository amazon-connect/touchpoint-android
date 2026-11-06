package com.amazon.connect.touchpoint.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

// The few SQL helpers the store needs over the androidx.sqlite driver interface. Bind indices are
// 1-based and column indices 0-based, as SQLite's own API has them.

internal fun SQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { i, arg ->
        val index = i + 1
        when (arg) {
            null -> bindNull(index)
            is String -> bindText(index, arg)
            is Long -> bindLong(index, arg)
            is Int -> bindLong(index, arg.toLong())
            is Boolean -> bindLong(index, if (arg) 1L else 0L)
            is ByteArray -> bindBlob(index, arg)
            else -> throw TouchpointException.PersistenceFailure("cannot bind ${arg.javaClass.simpleName}")
        }
    }
}

internal fun SQLiteStatement.textOrNull(column: Int): String? = if (isNull(column)) null else getText(column)

internal fun SQLiteConnection.execute(sql: String, vararg args: Any?) {
    prepare(sql).use { st ->
        st.bindAll(args)
        st.step()
    }
}

internal fun <T> SQLiteConnection.query(sql: String, vararg args: Any?, mapper: (SQLiteStatement) -> T): List<T> =
    prepare(sql).use { st ->
        st.bindAll(args)
        val out = ArrayList<T>()
        while (st.step()) out += mapper(st)
        out
    }

internal fun SQLiteConnection.exists(sql: String, vararg args: Any?): Boolean =
    prepare(sql).use { st ->
        st.bindAll(args)
        st.step()
    }

/**
 * Runs [block] in an immediate transaction: committed if it returns, rolled back if it — or the
 * commit itself — throws, so a failed COMMIT never leaves the connection inside a transaction.
 */
internal inline fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execute("BEGIN IMMEDIATE")
    try {
        val result = block()
        execute("COMMIT")
        return result
    } catch (e: Throwable) {
        ignoringFailure { execute("ROLLBACK") }
        throw e
    }
}
