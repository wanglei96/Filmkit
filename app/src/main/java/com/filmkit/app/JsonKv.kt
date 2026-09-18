package com.filmkit.app

/**
 * 构造日志 kv JSON 对象。手写转义以便 JVM 单元测试无 Android 依赖
 * （org.json 在 unit test 里不可用）。
 */
fun kv(vararg pairs: Pair<String, Any?>): String = buildString {
    append('{')
    pairs.forEachIndexed { i, (key, value) ->
        if (i > 0) append(',')
        append('"').append(jsonEscape(key)).append("\":")
        when (value) {
            null -> append("null")
            is Int, is Long, is Short, is Byte -> append(value)
            is Double, is Float -> append(value)
            is Boolean -> append(value)
            else -> append('"').append(jsonEscape(value.toString())).append('"')
        }
    }
    append('}')
}

fun jsonEscape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
