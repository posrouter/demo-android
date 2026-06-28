package com.posrouter.demo

object SecretMask {
    fun maskMiddle(value: String, prefixLen: Int = 6, suffixLen: Int = 4): String {
        if (value.isBlank()) return ""
        val prefix = value.take(prefixLen.coerceAtMost(value.length))
        val suffix = if (value.length > suffixLen) value.takeLast(suffixLen) else ""
        return prefix + "****" + suffix
    }
}
