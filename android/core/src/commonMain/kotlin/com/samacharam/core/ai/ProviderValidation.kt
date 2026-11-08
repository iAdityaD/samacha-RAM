package com.samacharam.core.ai

private val ENDPOINT = Regex("^(https?)://(\\[[0-9a-fA-F:.]+]|[^/?#:@]+)(:\\d{1,5})?(/[^?#]*)?$")

data class Endpoint(val scheme: String, val host: String, val port: Int?, val path: String)

fun parseEndpoint(raw: String): Endpoint {
    val m = ENDPOINT.matchEntire(raw.trim()) ?: throw AiException("Enter a base URL like https://host/v1, without credentials, query, or fragment.")
    val port = m.groupValues[3].removePrefix(":").ifEmpty { null }?.toInt()
    if (port != null && port !in 1..65535) throw AiException("Port must be 1–65535.")
    return Endpoint(m.groupValues[1].lowercase(), m.groupValues[2].lowercase().removeSurrounding("[", "]"), port, m.groupValues[4].trimEnd('/'))
}

/**
 * True for hosts that can only be on the owner's own network: loopback, RFC 1918 / unique-local
 * literals, and mDNS / home names. The Android transport re-checks resolved addresses at connect time.
 */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
    if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
    val octets = h.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val (a, b) = octets[0]!! to octets[1]!!
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    return '.' !in h // single-label names such as "my-desktop" only resolve on a local network
}

fun defaultEndpoint(protocol: Protocol): String? = when (protocol) {
    Protocol.Anthropic -> "https://api.anthropic.com"
    Protocol.Gemini -> "https://generativelanguage.googleapis.com"
    else -> null
}

fun ProviderConfig.effectiveEndpoint(): String? = endpoint?.takeIf { it.isNotBlank() } ?: defaultEndpoint(protocol)

fun ProviderConfig.location(): Location {
    if (protocol.onDevice) return Location.Device
    val endpoint = effectiveEndpoint() ?: throw AiException("$name needs an endpoint URL.")
    return if (isPrivateHost(parseEndpoint(endpoint).host)) Location.LocalNetwork else Location.Cloud
}

private val BUNDLE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")

/** Rejects configurations that could leak text, cannot work, or claim tasks the protocol does not offer. */
fun validateProvider(p: ProviderConfig) {
    if (p.id.isBlank() || p.id == BUILTIN || p.id.length > 64) throw AiException("Invalid provider identifier.")
    if (p.name.isBlank() || p.name.length > 100) throw AiException("A display name of 1–100 characters is required.")
    if (p.model.isBlank() || p.model.length > 300) throw AiException("A model identifier of 1–300 characters is required.")
    if (p.capabilities.isEmpty()) throw AiException("Select at least one task for this model.")
    val unsupported = p.capabilities - p.protocol.supports
    if (unsupported.isNotEmpty()) throw AiException("${p.protocol.name} does not support: ${unsupported.joinToString { it.wire }}.")
    if (p.maxInputChars !in 100..200_000) throw AiException("Input budget must be 100–200,000 characters.")
    if (p.maxOutputTokens !in 32..64_000) throw AiException("Output budget must be 32–64,000 tokens.")
    if (p.temperature !in 0.0..2.0) throw AiException("Temperature must be between 0 and 2.")
    if (p.timeoutMs !in 5_000..300_000) throw AiException("Timeout must be 5–300 seconds.")

    if (p.protocol.onDevice) {
        if (!BUNDLE.matches(p.model) || p.model == "." || p.model == "..") throw AiException("On-device models are referenced by their imported bundle name.")
        if (!p.endpoint.isNullOrBlank()) throw AiException("On-device models do not use an endpoint.")
        return
    }
    val endpoint = parseEndpoint(p.effectiveEndpoint() ?: throw AiException("${p.protocol.name} needs an endpoint URL."))
    when (if (isPrivateHost(endpoint.host)) Location.LocalNetwork else Location.Cloud) {
        Location.Cloud -> {
            if (endpoint.scheme != "https") throw AiException("Cloud endpoints must use HTTPS.")
            if (!p.allowCloud) throw AiException("Confirm that source text may be sent to ${endpoint.host} before saving.")
        }
        Location.LocalNetwork -> Unit // plain HTTP is allowed only because the host is on the owner's network
        Location.Device -> Unit
    }
}
