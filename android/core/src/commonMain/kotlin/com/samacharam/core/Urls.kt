package com.samacharam.core

private val URL_PATTERN = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$")
private val TRACKING_KEYS = setOf("fbclid", "gclid", "mc_cid", "mc_eid")

/**
 * Normalizes an HTTP(S) URL for identity and deduplication.
 *
 * Unlike the Python host, the path (including a trailing slash) is kept exactly:
 * many servers redirect `/path` to `/path/`, so stripping it breaks fetching.
 */
fun canonicalUrl(raw: String): String {
    val match = URL_PATTERN.matchEntire(raw.trim()) ?: throw IllegalArgumentException("Not a valid URL.")
    val scheme = match.groupValues[1].lowercase()
    if (scheme != "http" && scheme != "https") throw IllegalArgumentException("Only HTTP(S) URLs are supported.")
    val authority = match.groupValues[2]
    if ('@' in authority) throw IllegalArgumentException("URLs with embedded credentials are not accepted.")
    if (authority.isEmpty()) throw IllegalArgumentException("URL has no host.")
    var host = authority.lowercase()
    val defaultPort = if (scheme == "https") ":443" else ":80"
    if (host.endsWith(defaultPort) && !host.endsWith("]")) host = host.removeSuffix(defaultPort)
    val path = match.groupValues[3].ifEmpty { "/" }
    val query = match.groupValues[4].removePrefix("?")
        .split('&')
        .filter { it.isNotEmpty() }
        .filterNot { part ->
            val key = part.substringBefore('=').lowercase()
            key.startsWith("utm_") || key in TRACKING_KEYS
        }
        .joinToString("&")
    return "$scheme://$host$path" + if (query.isEmpty()) "" else "?$query"
}

/** 64-bit FNV-1a hash. Stable across platforms; used for compact identity columns. */
fun fnv64(value: String): Long {
    var hash = -3750763034362895579L // 0xcbf29ce484222325
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 1099511628211L
    }
    return hash
}

fun urlHash(canonical: String): Long = fnv64(canonical)
