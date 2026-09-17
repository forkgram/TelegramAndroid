package org.telegram.messenger.forkgram

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

internal object TrackingParameters {
    private val tracking = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "ttclid", "twclid",
        "yclid", "_ga", "_gl", "mc_cid", "mc_eid", "igshid", "erid"
    )
    private val protectedNames = setOf(
        "sig", "signature", "hmac", "policy", "key-pair-id", "token", "access_token", "id_token",
        "oauth_token", "oauth_signature", "authkey", "auth", "jwt", "expires", "x-oss-signature",
        "code", "state", "redirect_uri", "csrf", "nonce", "x_id", "payment_intent", "client_secret"
    )
    private val sensitivePath = Regex(
        """(?:^|[/_.-])(?:checkout|payments?|pay|login|sign-?in|oauth2?|auth|verify|activate|reset)(?:[/_.-]|$)""",
        RegexOption.IGNORE_CASE
    )

    private fun String.isDomain(domain: String) = this == domain || endsWith(".$domain")

    private fun parameterName(part: String): String? = try {
        URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clean(url: String): String {
        if (url.length > 10000 || '?' !in url) return url
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return url
        }
        if (!parsed.scheme.equals("https", true) && !parsed.scheme.equals("http", true)) return url
        val host = parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return url
        if (parsed.rawUserInfo != null || sensitivePath.containsMatchIn(parsed.path.orEmpty()) ||
            host.isDomain("t.me") || host.isDomain("telegram.me") || host.isDomain("telegram.org") ||
            host.isDomain("telegram.dog") || host.isDomain("paypal.com") || host.isDomain("stripe.com")
        ) return url

        val query = parsed.rawQuery ?: return url
        if (query.contains(';')) return url
        val parts = query.split('&')
        val names = parts.map(::parameterName)
        if (names.any {
                it in protectedNames || it?.startsWith("x-amz-") == true || it?.startsWith("x-goog-") == true
            }) return url
        val youtube = host.isDomain("youtube.com") || host == "youtu.be"
        val instagram = host.isDomain("instagram.com")
        val kept = parts.filterIndexed { index, _ ->
            val name = names[index]
            name !in tracking && name?.startsWith("utm_") != true &&
                !(name == "si" && youtube) && !(name == "igsh" && instagram)
        }
        if (kept.size == parts.size) return url
        val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
        val suffix = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return url.substringBefore('?') + suffix + fragment
    }
}
