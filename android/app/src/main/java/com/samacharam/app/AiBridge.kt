package com.samacharam.app

import android.content.Context
import com.samacharam.core.ai.AiException
import com.samacharam.core.ai.AiSettings
import com.samacharam.core.ai.HttpRequest
import com.samacharam.core.ai.HttpResponse
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.ai.OnDeviceRuntime
import com.samacharam.core.ai.SecretStore
import com.samacharam.core.ai.Transport
import com.samacharam.core.ai.UsageCounter
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** API keys in the Android Keystore-backed vault. Never synced, exported, or shown again after saving. */
class KeystoreSecrets(context: Context) : SecretStore {
    private val vault = SecureStore(context)
    override fun get(providerId: String): String? = vault.get("ai-key:$providerId")?.takeIf { it.isNotEmpty() }
    override fun put(providerId: String, secret: String) = vault.put("ai-key:$providerId", secret.trim())
    override fun remove(providerId: String) = vault.remove("ai-key:$providerId")
}

/** Address classes that only exist on the owner's own network (or this device). */
internal fun InetAddress.isPrivateNetwork(): Boolean {
    if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress) return true
    val b = address
    return when (this) {
        is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 // 100.64.0.0/10 (e.g. Tailscale)
        is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc // fc00::/7 unique local
        else -> false
    }
}

/** Raised when an endpoint resolves to addresses its location does not allow. */
private class BlockedAddressException(message: String) : UnknownHostException(message)

/**
 * Resolves once, validates every address for the request's [Location], and hands OkHttp exactly
 * those addresses, so DNS cannot be switched to a private address after the check (rebinding).
 */
private class ValidatingDns(private val location: Location) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val ok = when (location) {
            Location.Cloud -> addresses.none { it.isPrivateNetwork() || it.isAnyLocalAddress || it.isMulticastAddress }
            Location.LocalNetwork -> addresses.all { it.isPrivateNetwork() }
            Location.Device -> false
        }
        if (!ok || addresses.isEmpty()) {
            throw BlockedAddressException(
                if (location == Location.Cloud) "Cloud endpoints may not resolve to private addresses."
                else "Local-network endpoints must resolve to addresses on your own network."
            )
        }
        return addresses
    }
}

class OkHttpTransport : Transport {
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val cloud = base.newBuilder().dns(ValidatingDns(Location.Cloud)).build()
    private val local = base.newBuilder().dns(ValidatingDns(Location.LocalNetwork)).build()

    override fun send(request: HttpRequest): HttpResponse {
        val url = request.url.toHttpUrlOrNull() ?: throw AiException("Invalid model endpoint.")
        val client = when (request.location) {
            Location.Cloud -> cloud
            Location.LocalNetwork -> local
            Location.Device -> throw AiException("On-device models do not use the network.")
        }
        if (!url.isHttps && request.location != Location.LocalNetwork) throw AiException("Plain HTTP is only allowed to devices on your own network.")
        val call = client.newBuilder().callTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
            .newCall(
                Request.Builder().url(url).post(request.body.toRequestBody("application/json".toMediaType()))
                    .apply { request.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            )
        val executed = try { call.execute() } catch (e: BlockedAddressException) { throw AiException(e.message ?: "Endpoint address is not allowed.") }
        executed.use { response ->
            val source = response.body?.source() ?: return HttpResponse(response.code, "")
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw AiException("The model response was larger than ${MAX_RESPONSE_BYTES / 1_048_576} MB.")
            return HttpResponse(response.code, source.buffer.readUtf8())
        }
    }

    companion object { const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024 }
}

/** Bridges the router to the existing ONNX Runtime and LiteRT-LM adapters. No network access. */
class DeviceRuntime(private val models: DeviceModels) : OnDeviceRuntime {
    override fun generate(bundle: String, system: String, user: String, maxOutputTokens: Int): String =
        models.generate(bundle, "$system\n\n$user")

    override fun embed(bundle: String, text: String): FloatArray = models.embedding(models.bundleDir(bundle), text)

    override fun classify(bundle: String, text: String): Map<String, Float> = models.labelScores(models.bundleDir(bundle), text)
}

/** One router per app process. Settings and the usage counter come from the local library. */
fun modelRouter(context: Context, settings: () -> AiSettings, usage: UsageCounter): ModelRouter =
    ModelRouter(
        settings = settings,
        secrets = KeystoreSecrets(context),
        transport = OkHttpTransport(),
        usage = usage,
        onDevice = DeviceRuntime(DeviceModels(context)),
        clock = System::currentTimeMillis,
    )
