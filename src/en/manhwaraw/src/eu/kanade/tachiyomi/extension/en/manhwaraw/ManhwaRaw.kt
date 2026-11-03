package eu.kanade.tachiyomi.extension.en.manhwaraw

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.cronet.cronet
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Source
abstract class ManhwaRaw :
    KeiSource(),
    ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient() = this
        .cronet()
        .addCookie("pu_wex" to "true")
        .rateLimit(2)

    override fun Headers.Builder.configureHeaders() = setRandomUserAgent()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    @Volatile
    private var mbkToken: String = DEFAULT_MBK_TOKEN

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildPageUrl(baseUrl, page).newBuilder()
            .addQueryParameter("m_orderby", "views")
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaPage(document, includeSlider = page == 1)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = buildPageUrl(baseUrl, page)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaPage(document, includeSlider = false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val slug = slugifySearchQuery(query)
            if (slug.isEmpty()) return MangasPage(emptyList(), false)

            val url = buildPageUrl("$baseUrl/search/$mbkToken/$slug", page)
            val document = client.get(url, Fetch.DOCUMENT).asJsoup()
            return parseMangaPage(document, includeSlider = false)
        }

        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selected.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.selected.orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()

        val basePath = when {
            genre.isNotEmpty() -> "$baseUrl/manga-genre/$genre"
            tag.isNotEmpty() -> "$baseUrl/manga-tag/$tag"
            else -> baseUrl
        }

        val url = buildPageUrl(basePath, page).newBuilder().apply {
            if (sort.isNotEmpty()) {
                addQueryParameter("m_orderby", sort)
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaPage(document, includeSlider = false)
    }

    private fun parseMangaPage(document: Document, includeSlider: Boolean): MangasPage {
        document.selectFirst("input[name=mbk_token]")?.attrOrNull("value")?.let {
            mbkToken = it
        }

        val selector = if (includeSlider) {
            "div.slider__item, div.page-item-detail, div.c-tabs-item__content"
        } else {
            "div.page-item-detail, div.c-tabs-item__content"
        }

        val mangas = document.select(selector).mapNotNull { element ->
            val titleElement = element.selectFirst("div.post-title a") ?: return@mapNotNull null
            SManga.create().apply {
                title = titleElement.text()
                setUrlWithoutDomain(titleElement.absUrl("href"))
                thumbnail_url = element.selectFirst("div.item-thumb img, div.slider__thumb img, div.tab-thumb img")?.imgAttr()
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("div.wp-pagenavi a.nextpostslink, div.nav-previous a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        val manga = SManga.create().apply {
            this.url = "/manga/$slug/"
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, manga: SManga) = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("span.rate-title")?.attrOrNull("title")
            ?: document.selectFirst("#manga-title h1, div.post-title h1")!!.ownText()
                .substringBefore(">>")
                .trim()
        author = document.select("div.author-content > a").joinToString { it.text() }.ifBlank { null }
        artist = document.select("div.artist-content > a").joinToString { it.text() }.ifBlank { null }

        val summary = document.selectFirst("div.summary-container, div.summary__content")?.textOrNull()
        val altName = document.selectFirst("div.summary-heading:contains(Alternative) + div.summary-content")?.textOrNull()
            ?.takeIf { !it.equals("Updating", ignoreCase = true) }
        description = buildString {
            if (summary != null) append(summary)
            if (altName != null) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative: ").append(altName)
            }
        }.ifBlank { null }

        genre = buildList {
            document.selectFirst("div.summary-heading:contains(Type) + div.summary-content")?.textOrNull()
                ?.takeIf { !it.equals("Updating", ignoreCase = true) }
                ?.let(::add)
            document.select("div.genres-content a, div.tags-content a").mapTo(this) { it.text() }
        }.distinct().joinToString().ifBlank { null }

        status = parseStatus(
            document.selectFirst("div.summary-heading:contains(Status) + div.summary-content")?.text(),
        )
        thumbnail_url = document.selectFirst("div.summary_image img")?.imgAttr()
    }

    private fun parseStatus(statusText: String?): Int {
        val text = statusText?.lowercase(Locale.ROOT) ?: return SManga.UNKNOWN
        return when {
            "ongoing" in text -> SManga.ONGOING
            "completed" in text || "end" in text -> SManga.COMPLETED
            "on hold" in text || "hiatus" in text -> SManga.ON_HIATUS
            "canceled" in text || "cancelled" in text || "dropped" in text -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document): List<SChapter> = document.select("li.wp-manga-chapter").map { element ->
        SChapter.create().apply {
            val link = element.selectFirst("div.mini-letters a, a:not(.c-new-tag)")!!
            setUrlWithoutDomain(link.absUrl("href"))
            name = link.text()
            date_upload = parseChapterDate(element.selectFirst("span.chapter-release-date"))
        }
    }

    private fun parseChapterDate(dateElement: Element?): Long {
        if (dateElement == null) return 0L
        if (dateElement.selectFirst("a.c-new-tag") != null) {
            return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        val dateStr = dateElement.text().trim()
        if (dateStr.isEmpty()) return 0L

        val monthDay = runCatching { MonthDay.parse(dateStr, monthDayFormat) }.getOrNull()
        if (monthDay != null) {
            val today = LocalDate.now(ZoneOffset.UTC)
            val candidate = monthDay.atYear(today.year)
            val date = if (candidate.isAfter(today)) candidate.minusYears(1) else candidate
            return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        val match = relativeDateRegex.find(dateStr.lowercase(Locale.ROOT))
        if (match != null) {
            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = when (match.groupValues[2]) {
                "second", "seconds" -> ChronoUnit.SECONDS
                "minute", "minutes" -> ChronoUnit.MINUTES
                "hour", "hours" -> ChronoUnit.HOURS
                "day", "days" -> ChronoUnit.DAYS
                "week", "weeks" -> ChronoUnit.WEEKS
                "month", "months" -> ChronoUnit.MONTHS
                "year", "years" -> ChronoUnit.YEARS
                else -> return 0L
            }
            return ZonedDateTime.now(ZoneOffset.UTC).minus(amount, unit).toInstant().toEpochMilli()
        }

        return fullDateFormat.tryParseDate(dateStr)
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return document.select("div.page-break img.wp-manga-chapter-img, div.reading-content div.page-break img")
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.imgAttr() ?: return@mapIndexedNotNull null
                Page(index, imageUrl = imageUrl)
            }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching: Boolean = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/manga-tag/fin/", Fetch.DOCUMENT).asJsoup()
        val genres = document.select("div.genres ul li a[href*='/manga-genre/']").mapNotNull { link ->
            val name = link.ownText().trim().ifEmpty { return@mapNotNull null }
            val slug = link.attr("href").trimEnd('/').substringAfterLast('/').ifEmpty { return@mapNotNull null }
            GenreOption(name = name, slug = slug)
        }
        return genres.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreOption>>().orEmpty()
        return FilterList(
            buildList {
                add(SortFilter())
                add(TagFilter())
                if (genres.isNotEmpty()) {
                    add(GenreFilter(genres))
                }
            },
        )
    }

    // ============================= Utilities ==============================

    private fun buildPageUrl(basePath: String, page: Int): HttpUrl = basePath.toHttpUrl().newBuilder().apply {
        if (page > 1) {
            addPathSegment("page")
            addPathSegment(page.toString())
        }
        addPathSegment("")
    }.build()

    private fun slugifySearchQuery(query: String): String = Normalizer.normalize(query.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(diacriticsRegex, "")
        .replace(nonAlphanumRegex, "-")
        .trim('-')

    private fun Element.imgAttr(): String? = attrOrNull("abs:data-src")?.trim()
        ?: attrOrNull("abs:data-lazy-src")?.trim()
        ?: attrOrNull("abs:data-cfsrc")?.trim()
        ?: attrOrNull("abs:src")?.trim()
        ?: attrOrNull("src")?.trim()?.takeIf { it.startsWith("http") }

    companion object {
        private const val DEFAULT_MBK_TOKEN = "df929d81"
        private val diacriticsRegex = Regex("""[\u0300-\u036f]+""")
        private val nonAlphanumRegex = Regex("""[^a-z0-9]+""")
        private val relativeDateRegex = Regex("""(\d+)\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)""")
        private val monthDayFormat = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT)
        private val fullDateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    }
}
