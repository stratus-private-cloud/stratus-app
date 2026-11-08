package dev.stratus.core.cast

private val link = Regex("""https?://[^\s,)\]]+""")

/**
 * A cast trail as it may leave the phone (stratus-app#191). A link is a share
 * link, valid for a day, with the server and the file's path in it, so each
 * one is reduced to its scheme and its query without the signature: whether
 * it was https and which rendition was asked for is what a failure is
 * diagnosed by, and nothing else in it is ours to send.
 */
fun withoutLinks(message: String): String = link.replace(message) { match ->
    val url = match.value
    val scheme = url.substringBefore("://")
    val query = url.substringAfter('?', "")
        .split('&')
        .filter { it.isNotEmpty() && !it.startsWith("k=") }
    if (query.isEmpty()) "<$scheme link>" else "<$scheme link ${query.joinToString("&")}>"
}
