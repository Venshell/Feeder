package com.nononsenseapps.feeder.model

import java.net.URL

const val PAGINATION_MODE_AUTO = "AUTO"
const val PAGINATION_MODE_WORDPRESS = "WORDPRESS"
const val PAGINATION_MODE_OFF = "OFF"

/**
 * How a feed discovers pages older than the subscription document.
 *
 * [AUTO] uses an RFC 5005 link when the document has one, and otherwise WordPress
 * `?paged=` only when the feed generator identifies WordPress.
 * [WORDPRESS] always falls back to `?paged=` when no RFC 5005 link is present.
 * [OFF] never offers older pages.
 */
enum class FeedPaginationMode {
    AUTO,
    WORDPRESS,
    OFF,
    ;

    companion object {
        fun fromStored(value: String?): FeedPaginationMode =
            when (value?.uppercase()) {
                PAGINATION_MODE_WORDPRESS -> WORDPRESS
                PAGINATION_MODE_OFF -> OFF
                else -> AUTO
            }
    }
}

data class ExtractedFeedLinks(
    val nextUrl: String? = null,
    val prevArchiveUrl: String? = null,
    val generator: String? = null,
)

data class PaginationCursor(
    val loadedPage: Int = 1,
    val nextUrl: String = "",
    val exhausted: Boolean = false,
)

data class PaginationFields(
    val mode: String = PAGINATION_MODE_AUTO,
    val loadedPage: Int = 1,
    val nextUrl: String = "",
    val exhausted: Boolean = false,
)

sealed class OlderPageDecision {
    data class Inserted(
        val cursor: PaginationCursor,
    ) : OlderPageDecision()

    data class Exhausted(
        val cursor: PaginationCursor,
        val notFound: Boolean,
    ) : OlderPageDecision()
}

sealed class StatusDecision {
    data object Continue : StatusDecision()

    data object Retry : StatusDecision()

    data class Exhausted(
        val cursor: PaginationCursor,
    ) : StatusDecision()
}

enum class LoadOlderOutcome {
    Inserted,
    NoNewItems,
    NotFound,
    Failed,
    NothingToLoad,
}

enum class LoadOlderMessage {
    None,
    NoOlder,
    Failed,
}

data class LoadOlderUiState(
    val showAction: Boolean = false,
    val loading: Boolean = false,
    val message: LoadOlderMessage = LoadOlderMessage.None,
) {
    val visible: Boolean
        get() = loading || showAction || message != LoadOlderMessage.None
}

fun LoadOlderOutcome.toLoadOlderMessage(): LoadOlderMessage =
    when (this) {
        LoadOlderOutcome.Inserted, LoadOlderOutcome.NothingToLoad -> LoadOlderMessage.None
        LoadOlderOutcome.NoNewItems, LoadOlderOutcome.NotFound -> LoadOlderMessage.NoOlder
        LoadOlderOutcome.Failed -> LoadOlderMessage.Failed
    }

fun feedOffersOlderPages(
    mode: String,
    exhausted: Boolean,
    nextUrl: String,
): Boolean =
    FeedPaginationMode.fromStored(mode) != FeedPaginationMode.OFF &&
        !exhausted &&
        nextUrl.isNotBlank()

fun loadOlderUiState(
    offersOlder: Boolean,
    loading: Boolean,
    message: LoadOlderMessage,
): LoadOlderUiState =
    LoadOlderUiState(
        showAction = offersOlder && !loading && message != LoadOlderMessage.NoOlder,
        loading = loading,
        message = message,
    )

/**
 * Page index stored on a newly inserted row. Existing rows keep whatever page they
 * were first inserted from, so a later sync cannot pull an archived article back
 * into the max-items window. Live sync passes [requestedPage] 0 and changes nothing.
 */
fun loadedFromPageForUpsert(
    existingItemId: Long,
    requestedPage: Int,
): Int {
    if (requestedPage <= 0) return 0
    if (existingItemId > 0L) return 0
    return requestedPage
}

/**
 * Max-items cleanup counts only live-sync rows. Bookmarks and rows inserted from an
 * older page are kept regardless of the per-feed cap.
 */
fun isExemptFromMaxItemCleanup(
    loadedFromPage: Int,
    bookmarked: Boolean,
): Boolean = bookmarked || loadedFromPage > 0

fun looksLikeWordPress(generator: String?): Boolean {
    if (generator.isNullOrBlank()) return false
    return "wordpress" in generator.lowercase()
}

fun extractFeedPagination(body: ByteArray): ExtractedFeedLinks = extractFeedPagination(decodeFeedBody(body))

fun extractFeedPagination(body: String): ExtractedFeedLinks {
    val text = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    if (text.startsWith("{")) {
        return ExtractedFeedLinks(
            nextUrl = topLevelJsonString(text, "next_url"),
            generator = topLevelJsonString(text, "generator"),
        )
    }
    return extractXmlFeedPagination(text)
}

/**
 * URL of the next older page, or null when this document does not expose one.
 *
 * RFC 5005 wins: `rel="next"` (paged feeds), then `rel="prev-archive"` (archived feeds).
 * Otherwise WordPress `?paged=` when the mode or the generator says to use it.
 * A link that points back at the document just fetched, or back at the subscription
 * URL, is ignored so a mistaken self link cannot spin.
 */
fun olderPageUrl(
    feedUrl: String,
    documentUrl: String,
    links: ExtractedFeedLinks,
    mode: FeedPaginationMode,
    loadedPage: Int,
): String? {
    if (mode == FeedPaginationMode.OFF) return null
    val rfcHref = links.nextUrl?.takeIf { it.isNotBlank() } ?: links.prevArchiveUrl?.takeIf { it.isNotBlank() }
    val candidate =
        if (rfcHref != null) {
            resolveHref(documentUrl, rfcHref)
        } else if (mode == FeedPaginationMode.WORDPRESS || looksLikeWordPress(links.generator)) {
            wordpressPagedUrl(feedUrl, nextWordpressPageNumber(feedUrl, loadedPage))
        } else {
            null
        }
    if (candidate.isNullOrBlank()) return null
    if (sameUrl(candidate, documentUrl)) return null
    if (!sameUrl(documentUrl, feedUrl) && sameUrl(candidate, feedUrl)) return null
    return candidate
}

/**
 * Cursor to persist after a successful sync of the subscription document.
 * A walk that has already moved past page 1 is left in place: the live document's
 * `rel="next"` still points at the first older page, which has already been stored.
 */
fun cursorAfterLiveSync(
    feedUrl: String,
    links: ExtractedFeedLinks,
    mode: FeedPaginationMode,
    previous: PaginationCursor,
): PaginationCursor {
    if (mode == FeedPaginationMode.OFF || previous.exhausted) return previous
    if (previous.loadedPage > 1) {
        if (previous.nextUrl.isNotBlank()) return previous
        val filled =
            olderPageUrl(
                feedUrl = feedUrl,
                documentUrl = feedUrl,
                links = links,
                mode = mode,
                loadedPage = previous.loadedPage,
            )
        return if (filled.isNullOrBlank()) previous else previous.copy(nextUrl = filled)
    }
    val next =
        olderPageUrl(
            feedUrl = feedUrl,
            documentUrl = feedUrl,
            links = links,
            mode = mode,
            loadedPage = 1,
        )
    return PaginationCursor(
        loadedPage = 1,
        nextUrl = next.orEmpty(),
        exhausted = false,
    )
}

fun decisionForStatusCode(
    previous: PaginationCursor,
    code: Int,
): StatusDecision =
    when (code) {
        404, 410 ->
            StatusDecision.Exhausted(
                cursor =
                    previous.copy(
                        loadedPage = previous.loadedPage + 1,
                        nextUrl = "",
                        exhausted = true,
                    ),
            )

        in 200..299 -> StatusDecision.Continue
        else -> StatusDecision.Retry
    }

/**
 * What to persist after a parsed older page.
 * A page with no newly inserted items ends the walk. A page that inserted items
 * but has no further link also ends it, without reporting an error.
 */
fun decisionForParsedPage(
    feedUrl: String,
    fetchedUrl: String,
    links: ExtractedFeedLinks,
    mode: FeedPaginationMode,
    previous: PaginationCursor,
    parsedItemCount: Int,
    newItemCount: Int,
): OlderPageDecision {
    val pageJustLoaded = previous.loadedPage + 1
    if (parsedItemCount <= 0 || newItemCount <= 0) {
        return OlderPageDecision.Exhausted(
            cursor =
                previous.copy(
                    loadedPage = pageJustLoaded,
                    nextUrl = "",
                    exhausted = true,
                ),
            notFound = false,
        )
    }
    val next =
        olderPageUrl(
            feedUrl = feedUrl,
            documentUrl = fetchedUrl,
            links = links,
            mode = mode,
            loadedPage = pageJustLoaded,
        )
    return OlderPageDecision.Inserted(
        cursor =
            PaginationCursor(
                loadedPage = pageJustLoaded,
                nextUrl = next.orEmpty(),
                exhausted = next.isNullOrBlank(),
            ),
    )
}

/**
 * Pagination columns written when the user saves the feed editor.
 * Changing the URL starts the walk over. Changing the mode clears exhaustion so a
 * finished walk can be retried, and fills a WordPress next URL immediately.
 */
fun paginationFieldsOnSave(
    previous: PaginationFields,
    newMode: String,
    urlChanged: Boolean,
    feedUrl: String,
): PaginationFields {
    val normalized = FeedPaginationMode.fromStored(newMode).name
    if (urlChanged) {
        return fillWordpressNext(
            PaginationFields(mode = normalized, loadedPage = 1, nextUrl = "", exhausted = false),
            feedUrl,
        )
    }
    if (previous.mode == normalized) return previous
    return fillWordpressNext(
        previous.copy(mode = normalized, exhausted = false),
        feedUrl,
    )
}

fun wordpressPagedUrl(
    feedUrl: String,
    page: Int,
): String? {
    if (page < 1) return null
    val hash = feedUrl.indexOf('#')
    val fragment = if (hash >= 0) feedUrl.substring(hash) else ""
    val withoutFragment = if (hash >= 0) feedUrl.substring(0, hash) else feedUrl
    val queryStart = withoutFragment.indexOf('?')
    val base = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
    val query = if (queryStart >= 0) withoutFragment.substring(queryStart + 1) else ""
    val parts =
        query
            .split('&')
            .filter { part ->
                part.isNotEmpty() && !part.substringBefore('=').equals("paged", ignoreCase = true)
            }.toMutableList()
    if (page > 1) {
        parts.add("paged=$page")
    }
    val result =
        if (parts.isEmpty()) {
            base + fragment
        } else {
            base + "?" + parts.joinToString("&") + fragment
        }
    return try {
        URL(result)
        result
    } catch (_: Exception) {
        null
    }
}

fun nextWordpressPageNumber(
    feedUrl: String,
    loadedPage: Int,
): Int {
    val explicit = explicitPaged(feedUrl)
    return if (explicit != null) explicit + loadedPage else loadedPage + 1
}

internal fun explicitPaged(feedUrl: String): Int? {
    val withoutFragment = feedUrl.substringBefore('#')
    val queryStart = withoutFragment.indexOf('?')
    if (queryStart < 0) return null
    val query = withoutFragment.substring(queryStart + 1)
    val raw =
        query.split('&').firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=')
            if (name.equals("paged", ignoreCase = true)) part.substringAfter('=', "") else null
        } ?: return null
    return raw.toIntOrNull()?.takeIf { it >= 1 }
}

internal fun resolveHref(
    baseUrl: String,
    href: String,
): String? {
    val trimmed = unescapeXml(href.trim())
    if (trimmed.isEmpty()) return null
    return try {
        URL(URL(baseUrl), trimmed).toString()
    } catch (_: Exception) {
        null
    }
}

internal fun sameUrl(
    a: String,
    b: String,
): Boolean {
    val left =
        try {
            URL(a)
        } catch (_: Exception) {
            return a == b
        }
    val right =
        try {
            URL(b)
        } catch (_: Exception) {
            return a == b
        }
    return left.protocol.equals(right.protocol, ignoreCase = true) &&
        left.host.equals(right.host, ignoreCase = true) &&
        left.port == right.port &&
        left.path.trimEnd('/') == right.path.trimEnd('/') &&
        (left.query ?: "") == (right.query ?: "")
}

private fun fillWordpressNext(
    fields: PaginationFields,
    feedUrl: String,
): PaginationFields {
    if (fields.mode != PAGINATION_MODE_WORDPRESS || fields.nextUrl.isNotBlank()) return fields
    val next = wordpressPagedUrl(feedUrl, nextWordpressPageNumber(feedUrl, fields.loadedPage))
    return if (next.isNullOrBlank()) fields else fields.copy(nextUrl = next)
}

private fun decodeFeedBody(body: ByteArray): String {
    if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) {
        return body.toString(Charsets.UTF_16LE).trimStart('\uFEFF')
    }
    if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) {
        return body.toString(Charsets.UTF_16BE).trimStart('\uFEFF')
    }
    val header = body.copyOfRange(0, minOf(body.size, 240)).toString(Charsets.ISO_8859_1)
    val encoding =
        Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
    val charset =
        when (encoding?.lowercase()) {
            null, "utf-8", "utf8" -> Charsets.UTF_8
            "iso-8859-1", "latin1", "latin-1" -> Charsets.ISO_8859_1
            "us-ascii", "ascii" -> Charsets.US_ASCII
            else ->
                try {
                    charset(encoding)
                } catch (_: Exception) {
                    Charsets.UTF_8
                }
        }
    return body.toString(charset).trimStart('\uFEFF')
}

private fun extractXmlFeedPagination(xml: String): ExtractedFeedLinks {
    val preamble = feedPreamble(xml)
    var nextUrl: String? = null
    var prevArchiveUrl: String? = null
    for (match in linkTagRegex.findAll(preamble)) {
        val attrs = parseAttributes(match.groupValues[1])
        val href = attrs["href"]?.let(::unescapeXml)?.trim().orEmpty()
        if (href.isEmpty()) continue
        when (attrs["rel"]?.trim()?.lowercase()) {
            "next" -> if (nextUrl == null) nextUrl = href
            "prev-archive" -> if (prevArchiveUrl == null) prevArchiveUrl = href
        }
    }
    val generatorMatch = generatorRegex.find(preamble)
    val rawGenerator =
        generatorMatch?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: match.groupValues.getOrNull(2)
        }
    val generator = rawGenerator?.let(::unescapeXml)?.trim()?.ifBlank { null }
    return ExtractedFeedLinks(
        nextUrl = nextUrl,
        prevArchiveUrl = prevArchiveUrl,
        generator = generator,
    )
}

private fun feedPreamble(xml: String): String {
    val item = itemOrEntryStart.find(xml) ?: return xml
    return xml.substring(0, item.range.first)
}

private fun parseAttributes(source: String): Map<String, String> {
    val attrs = linkedMapOf<String, String>()
    for (match in attributeRegex.findAll(source)) {
        val name = match.groupValues[1].substringAfter(':').lowercase()
        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
        attrs.putIfAbsent(name, value)
    }
    return attrs
}

private fun unescapeXml(value: String): String =
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

internal fun topLevelJsonString(
    json: String,
    wantedKey: String,
): String? {
    var i = 0
    val n = json.length
    var depth = 0
    while (i < n) {
        val c = json[i]
        if (c == '"') {
            val read = readJsonString(json, i) ?: return null
            i = read.next
            if (depth == 1) {
                var j = i
                while (j < n && json[j].isWhitespace()) j++
                if (j < n && json[j] == ':') {
                    j++
                    while (j < n && json[j].isWhitespace()) j++
                    if (read.value == wantedKey && j < n && json[j] == '"') {
                        return readJsonString(json, j)?.value
                    }
                }
            }
            continue
        }
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> if (depth > 0) depth--
        }
        i++
    }
    return null
}

private data class JsonStringRead(
    val value: String,
    val next: Int,
)

private fun readJsonString(
    json: String,
    startQuote: Int,
): JsonStringRead? {
    if (startQuote >= json.length || json[startQuote] != '"') return null
    val out = StringBuilder()
    var i = startQuote + 1
    while (i < json.length) {
        val c = json[i]
        when (c) {
            '\\' -> {
                if (i + 1 >= json.length) return null
                when (val escaped = json[i + 1]) {
                    '"', '\\', '/' -> {
                        out.append(escaped)
                        i += 2
                    }

                    'b' -> {
                        out.append('\b')
                        i += 2
                    }

                    'f' -> {
                        out.append('\u000c')
                        i += 2
                    }

                    'n' -> {
                        out.append('\n')
                        i += 2
                    }

                    'r' -> {
                        out.append('\r')
                        i += 2
                    }

                    't' -> {
                        out.append('\t')
                        i += 2
                    }

                    'u' -> {
                        if (i + 5 >= json.length) return null
                        val hex = json.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 6
                    }

                    else -> {
                        out.append(escaped)
                        i += 2
                    }
                }
            }

            '"' -> return JsonStringRead(out.toString(), i + 1)
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return null
}

private val linkTagRegex =
    Regex(
        """<\s*(?:[A-Za-z0-9._-]+:)?link\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )

private val attributeRegex =
    Regex(
        """([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )

private val generatorRegex =
    Regex(
        """<\s*(?:[A-Za-z0-9._-]+:)?generator\b[^>]*>\s*(?:<!\[CDATA\[(.*?)]]>|([^<]*))\s*</\s*(?:[A-Za-z0-9._-]+:)?generator\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val itemOrEntryStart =
    Regex(
        """<\s*(?:[A-Za-z0-9._-]+:)?(?:item|entry)\b""",
        RegexOption.IGNORE_CASE,
    )
