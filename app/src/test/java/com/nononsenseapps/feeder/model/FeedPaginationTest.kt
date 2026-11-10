package com.nononsenseapps.feeder.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedPaginationTest {
    private val cdt = "https://chinadigitaltimes.net/chinese/feed/"

    @Test
    fun wordpressPagedUrlPreservesTrailingSlashAndReplacesExistingPage() {
        assertEquals(
            "$cdt?paged=2",
            wordpressPagedUrl(cdt, 2),
        )
        assertEquals(
            "https://example.com/feed?paged=2",
            wordpressPagedUrl("https://example.com/feed", 2),
        )
        assertEquals(
            "https://example.com/feed/?foo=1&paged=3",
            wordpressPagedUrl("https://example.com/feed/?paged=2&foo=1", 3),
        )
        assertEquals(
            "https://user:secret@example.com:8443/feed/?paged=4",
            wordpressPagedUrl("https://user:secret@example.com:8443/feed/?paged=2", 4),
        )
        assertEquals(
            "https://example.com/feed/",
            wordpressPagedUrl("$cdt".replace(cdt, "https://example.com/feed/?paged=2"), 1),
        )
    }

    @Test
    fun subscribedPagedUrlContinuesAfterThatPage() {
        val subscribed = "$cdt?paged=2"
        assertEquals(3, nextWordpressPageNumber(subscribed, loadedPage = 1))
        assertEquals(
            "$cdt?paged=3",
            olderPageUrl(
                feedUrl = subscribed,
                documentUrl = subscribed,
                links = ExtractedFeedLinks(),
                mode = FeedPaginationMode.WORDPRESS,
                loadedPage = 1,
            ),
        )
    }

    @Test
    fun rfcNextBeatsPrevArchiveAndResolvesRelativeLinks() {
        val links =
            ExtractedFeedLinks(
                nextUrl = "index.atom?page=2",
                prevArchiveUrl = "https://example.org/old.atom",
            )
        assertEquals(
            "https://example.org/index.atom?page=2",
            olderPageUrl(
                feedUrl = "https://example.org/index.atom",
                documentUrl = "https://example.org/index.atom",
                links = links,
                mode = FeedPaginationMode.AUTO,
                loadedPage = 1,
            ),
        )

        assertEquals(
            "https://example.org/2003/11/index.atom",
            olderPageUrl(
                feedUrl = "https://example.org/index.atom",
                documentUrl = "https://example.org/index.atom",
                links = ExtractedFeedLinks(prevArchiveUrl = "2003/11/index.atom"),
                mode = FeedPaginationMode.AUTO,
                loadedPage = 1,
            ),
        )
    }

    @Test
    fun selfLinkAndOffModeDoNotOfferAPage() {
        val feed = "https://example.org/index.atom"
        assertNull(
            olderPageUrl(
                feedUrl = feed,
                documentUrl = feed,
                links = ExtractedFeedLinks(nextUrl = feed),
                mode = FeedPaginationMode.AUTO,
                loadedPage = 1,
            ),
        )
        assertNull(
            olderPageUrl(
                feedUrl = cdt,
                documentUrl = cdt,
                links = ExtractedFeedLinks(generator = "https://wordpress.org/?v=6.8.2"),
                mode = FeedPaginationMode.OFF,
                loadedPage = 1,
            ),
        )
    }

    @Test
    fun xmlExtractionIgnoresLinksInsideItemsAndReadsWordPressGenerator() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <atom:link href="https://chinadigitaltimes.net/chinese/feed/" rel="self" type="application/rss+xml" />
                <generator>https://wordpress.org/?v=6.8.2</generator>
                <item>
                  <atom:link href="https://evil.example/feed/?paged=99" rel="next" />
                  <title>not a page link</title>
                </item>
              </channel>
            </rss>
            """.trimIndent()

        val links = extractFeedPagination(xml)
        assertNull(links.nextUrl)
        assertTrue(looksLikeWordPress(links.generator))
        assertEquals(
            "$cdt?paged=2",
            olderPageUrl(
                feedUrl = cdt,
                documentUrl = cdt,
                links = links,
                mode = FeedPaginationMode.AUTO,
                loadedPage = 1,
            ),
        )
    }

    @Test
    fun xmlExtractionReadsRfcLinksAndCdataGenerator() {
        val xml =
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <link rel="self" href="https://example.org/index.atom"/>
              <link href="https://example.org/index.atom?page=2" rel="next"/>
              <link rel="prev-archive" href="https://example.org/archive.atom"/>
              <generator><![CDATA[WordPress 6.4]]></generator>
              <entry><link rel="next" href="https://evil.example/nope"/></entry>
            </feed>
            """.trimIndent()

        val links = extractFeedPagination(xml)
        assertEquals("https://example.org/index.atom?page=2", links.nextUrl)
        assertEquals("https://example.org/archive.atom", links.prevArchiveUrl)
        assertEquals("WordPress 6.4", links.generator)
    }

    @Test
    fun jsonNextUrlIsTopLevelOnly() {
        val json =
            """
            {"version":"https://jsonfeed.org/version/1.1","title":"Example","next_url":"https://example.com/feed.json?page=2","items":[{"title":"decoy","content_text":"\"next_url\": \"https://evil.example/nope\""}]}
            """.trimIndent()

        assertEquals(
            "https://example.com/feed.json?page=2",
            extractFeedPagination(json).nextUrl,
        )
        assertEquals(
            "https://example.com/a/b",
            topLevelJsonString("""{"next_url":"https:\/\/example.com\/a\/b"}""", "next_url"),
        )
    }

    @Test
    fun liveSyncRefreshesPageOneAndDoesNotRewindADeeperWalk() {
        val wordpress =
            ExtractedFeedLinks(generator = "https://wordpress.org/?v=6.8.2")
        val first =
            cursorAfterLiveSync(
                feedUrl = cdt,
                links = wordpress,
                mode = FeedPaginationMode.AUTO,
                previous = PaginationCursor(),
            )
        assertEquals(1, first.loadedPage)
        assertEquals("$cdt?paged=2", first.nextUrl)
        assertFalse(first.exhausted)

        val deep =
            PaginationCursor(
                loadedPage = 4,
                nextUrl = "$cdt?paged=5",
                exhausted = false,
            )
        assertEquals(
            deep,
            cursorAfterLiveSync(
                feedUrl = cdt,
                links = ExtractedFeedLinks(nextUrl = "$cdt?paged=2"),
                mode = FeedPaginationMode.AUTO,
                previous = deep,
            ),
        )

        val exhausted = deep.copy(exhausted = true, nextUrl = "")
        assertEquals(
            exhausted,
            cursorAfterLiveSync(
                feedUrl = cdt,
                links = wordpress,
                mode = FeedPaginationMode.AUTO,
                previous = exhausted,
            ),
        )
    }

    @Test
    fun parsedPageInsertsNewItemsAndStopsWhenNothingNewOrMissing() {
        val previous = PaginationCursor(loadedPage = 1, nextUrl = "$cdt?paged=2")
        val inserted =
            decisionForParsedPage(
                feedUrl = cdt,
                fetchedUrl = "$cdt?paged=2",
                links = ExtractedFeedLinks(generator = "https://wordpress.org/?v=6.8"),
                mode = FeedPaginationMode.AUTO,
                previous = previous,
                parsedItemCount = 7,
                newItemCount = 7,
            )
        val insertedCursor = (inserted as OlderPageDecision.Inserted).cursor
        assertEquals(2, insertedCursor.loadedPage)
        assertEquals("$cdt?paged=3", insertedCursor.nextUrl)
        assertFalse(insertedCursor.exhausted)

        val duplicates =
            decisionForParsedPage(
                feedUrl = cdt,
                fetchedUrl = "$cdt?paged=2",
                links = ExtractedFeedLinks(generator = "https://wordpress.org/?v=6.8"),
                mode = FeedPaginationMode.AUTO,
                previous = previous,
                parsedItemCount = 7,
                newItemCount = 0,
            ) as OlderPageDecision.Exhausted
        assertTrue(duplicates.cursor.exhausted)
        assertFalse(duplicates.notFound)
        assertEquals("", duplicates.cursor.nextUrl)

        val empty =
            decisionForParsedPage(
                feedUrl = cdt,
                fetchedUrl = "$cdt?paged=10",
                links = ExtractedFeedLinks(),
                mode = FeedPaginationMode.WORDPRESS,
                previous = PaginationCursor(loadedPage = 9, nextUrl = "$cdt?paged=10"),
                parsedItemCount = 0,
                newItemCount = 0,
            ) as OlderPageDecision.Exhausted
        assertEquals(10, empty.cursor.loadedPage)
        assertTrue(empty.cursor.exhausted)
    }

    @Test
    fun httpStatusStopsOnNotFoundAndRetriesOtherErrors() {
        val previous = PaginationCursor(loadedPage = 2, nextUrl = "$cdt?paged=3")
        val missing = decisionForStatusCode(previous, 404) as StatusDecision.Exhausted
        assertTrue(missing.cursor.exhausted)
        assertEquals(3, missing.cursor.loadedPage)

        assertTrue(decisionForStatusCode(previous, 410) is StatusDecision.Exhausted)
        assertTrue(decisionForStatusCode(previous, 500) is StatusDecision.Retry)
        assertTrue(decisionForStatusCode(previous, 200) is StatusDecision.Continue)
    }

    @Test
    fun archiveInsertMarksOnlyNewRowsAndLeavesThemOutOfMaxItemCleanup() {
        assertEquals(0, loadedFromPageForUpsert(existingItemId = 0, requestedPage = 0))
        assertEquals(0, loadedFromPageForUpsert(existingItemId = 12, requestedPage = 3))
        assertEquals(3, loadedFromPageForUpsert(existingItemId = 0, requestedPage = 3))

        assertFalse(isExemptFromMaxItemCleanup(loadedFromPage = 0, bookmarked = false))
        assertTrue(isExemptFromMaxItemCleanup(loadedFromPage = 2, bookmarked = false))
        assertTrue(isExemptFromMaxItemCleanup(loadedFromPage = 0, bookmarked = true))
    }

    @Test
    fun savingTheEditorFillsWordPressUrlAndKeepsAnUnchangedWalk() {
        val kept =
            PaginationFields(
                mode = PAGINATION_MODE_AUTO,
                loadedPage = 3,
                nextUrl = "$cdt?paged=4",
                exhausted = false,
            )
        assertEquals(
            kept,
            paginationFieldsOnSave(
                previous = kept,
                newMode = PAGINATION_MODE_AUTO,
                urlChanged = false,
                feedUrl = cdt,
            ),
        )

        val forced =
            paginationFieldsOnSave(
                previous = kept.copy(nextUrl = "", exhausted = true),
                newMode = PAGINATION_MODE_WORDPRESS,
                urlChanged = false,
                feedUrl = cdt,
            )
        assertEquals(PAGINATION_MODE_WORDPRESS, forced.mode)
        assertEquals(3, forced.loadedPage)
        assertEquals("$cdt?paged=4", forced.nextUrl)
        assertFalse(forced.exhausted)

        val reset =
            paginationFieldsOnSave(
                previous = kept,
                newMode = PAGINATION_MODE_WORDPRESS,
                urlChanged = true,
                feedUrl = "https://example.com/feed",
            )
        assertEquals(1, reset.loadedPage)
        assertEquals("https://example.com/feed?paged=2", reset.nextUrl)
        assertFalse(reset.exhausted)
    }

    @Test
    fun uiHidesTheActionOnceTheWalkIsFinishedOrLoading() {
        assertTrue(
            feedOffersOlderPages(
                mode = PAGINATION_MODE_AUTO,
                exhausted = false,
                nextUrl = "$cdt?paged=2",
            ),
        )
        assertFalse(
            feedOffersOlderPages(
                mode = PAGINATION_MODE_OFF,
                exhausted = false,
                nextUrl = "$cdt?paged=2",
            ),
        )

        val idle =
            loadOlderUiState(
                offersOlder = true,
                loading = false,
                message = LoadOlderMessage.None,
            )
        assertTrue(idle.showAction)
        assertTrue(idle.visible)

        val loading =
            loadOlderUiState(
                offersOlder = true,
                loading = true,
                message = LoadOlderMessage.None,
            )
        assertFalse(loading.showAction)
        assertTrue(loading.loading)

        val finished =
            loadOlderUiState(
                offersOlder = false,
                loading = false,
                message = LoadOlderOutcome.NoNewItems.toLoadOlderMessage(),
            )
        assertFalse(finished.showAction)
        assertEquals(LoadOlderMessage.NoOlder, finished.message)
        assertTrue(finished.visible)

        assertEquals(LoadOlderMessage.Failed, LoadOlderOutcome.Failed.toLoadOlderMessage())
        assertEquals(LoadOlderMessage.None, LoadOlderOutcome.Inserted.toLoadOlderMessage())
    }
}
