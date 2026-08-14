package com.hsucode.tools

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset

/** Network boundary for agent tools. Private and local addresses are never reachable. */
object NetworkUrlPolicy {
    private const val MAX_REDIRECTS = 5

    fun secureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .dns(PublicDns)
        .followRedirects(false)
        .followSslRedirects(false)

    /** Validates every redirect target before it is requested. Caller owns the returned response. */
    fun executeGet(client: OkHttpClient, initialUrl: String, headers: Map<String, String> = emptyMap()): Response {
        var target = validatedUri(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder().url(target.toASCIIString()).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            val response = client.newCall(request).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            if (redirectCount == MAX_REDIRECTS) throw IllegalArgumentException("重定向次数超过 $MAX_REDIRECTS 次")
            if (location.isNullOrBlank()) throw IllegalArgumentException("重定向响应缺少 Location")
            target = validatedUri(target.resolve(location).toString())
        }
        throw IllegalStateException("无法完成请求")
    }

    /** Reads a response in a bounded stream so a hostile HTML page cannot exhaust memory. */
    fun readTextLimited(body: ResponseBody, maxBytes: Long): String {
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        val declared = body.contentLength()
        if (declared > maxBytes) throw IllegalArgumentException("响应超过 ${maxBytes / 1024 / 1024} MB 限制")
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).toInt())
        body.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalArgumentException("响应超过 ${maxBytes / 1024 / 1024} MB 限制")
                output.write(buffer, 0, read)
            }
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return output.toString(charset.name())
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (bytes.size == 16 && bytes[0].toInt() and 0xff in 0xfc..0xfd) return false // IPv6 ULA
        if (bytes.size == 16 && bytes.copyOfRange(0, 12).contentEquals(IPV4_MAPPED_PREFIX)) {
            return isPublicIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff)
        }
        if (bytes.size == 4) return isPublicIpv4(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff)
        return true
    }

    private fun validatedUri(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("URL 无效") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "只允许 http/https URL" }
        require(uri.host?.isNotBlank() == true && uri.userInfo == null) { "URL 必须包含有效公网主机" }
        val host = uri.host.trim('[', ']').lowercase()
        require(!host.endsWith(".local") && host != "localhost") { "不允许访问本地网络地址" }
        return uri
    }

    private fun isPublicIpv4(first: Int, second: Int): Boolean = when {
        first == 0 || first == 10 || first == 127 -> false
        first == 100 && second in 64..127 -> false // carrier-grade NAT
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && (second == 0 || second == 168) -> false
        first == 198 && second in 18..19 -> false
        first >= 224 -> false
        else -> true
    }

    private object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname).filter(::isPublicAddress)
            if (addresses.isEmpty()) throw UnknownHostException("拒绝访问非公网地址: $hostname")
            return addresses
        }
    }

    private val IPV4_MAPPED_PREFIX = byteArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte()
    )
}
