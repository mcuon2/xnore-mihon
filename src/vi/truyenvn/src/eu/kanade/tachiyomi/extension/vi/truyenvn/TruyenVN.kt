package eu.kanade.tachiyomi.extension.vi.truyenvn

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
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
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Source
abstract class TruyenVN : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    // Image CDN (imggo.net) uses referrerpolicy="no-referrer" and returns 403 if Referer is sent
    override fun Headers.Builder.configureHeaders() = removeAll("Referer")

    private val ajaxHeaders: Headers
        get() = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(defaultOrder = 2)))

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/truyen-tranh".toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaPage(document)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }
            filters.firstInstanceOrNull<SortFilter>()?.let {
                addQueryParameter("m_orderby", it.selected)
            }
            filters.firstInstanceOrNull<StatusFilter>()?.state
                ?.filter { it.state }
                ?.forEach { addQueryParameter("state[]", it.id) }
            filters.firstInstanceOrNull<GenreFilter>()?.state
                ?.filter { it.state }
                ?.forEach { addQueryParameter("genre[]", it.id) }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaPage(document)
    }

    private fun parseMangaPage(document: Document): MangasPage {
        val mangas = document.select("div.page-item-detail, div.c-tabs-item__content").map { element ->
            SManga.create().apply {
                val titleElement = element.selectFirst("div.post-title a")!!
                title = titleElement.text()
                setUrlWithoutDomain(titleElement.absUrl("href"))
                thumbnail_url = element.selectFirst("div.item-thumb img, div.tab-thumb img")?.imgAttr()
            }
        }
        val hasNextPage = document.selectFirst("div.wp-pagenavi a.nextpostslink, div.nav-previous a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "truyen-tranh") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        val manga = SManga.create().apply {
            this.url = "/truyen-tranh/$slug"
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl, Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(document, mangaUrl) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, manga: SManga) = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("div.post-title h1")!!.text()
        author = document.selectFirst("div.summary-heading:contains(Tác giả) + div.summary-content")?.textOrNull()
        artist = document.selectFirst("div.summary-heading:contains(Họa sĩ) + div.summary-content")?.textOrNull()
        description = document.selectFirst("div.summary__content")?.textOrNull()
        genre = document.select("div.genres-content a[rel=tag]").joinToString { it.text() }
        status = parseStatus(
            document.selectFirst("div.summary-heading:contains(Tình trạng) + div.summary-content")?.text(),
        )
        thumbnail_url = document.selectFirst("div.summary_image img")?.imgAttr()
    }

    private fun parseStatus(statusText: String?): Int {
        val text = statusText?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "ongoing" in text || "đang ra" in text || "đang tiến hành" in text -> SManga.ONGOING
            "completed" in text || "hoàn thành" in text -> SManga.COMPLETED
            "on hold" in text || "tạm ngưng" in text -> SManga.ON_HIATUS
            "canceled" in text || "đã hủy" in text -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================

    private suspend fun fetchChapterList(document: Document, mangaUrl: String): List<SChapter> {
        // #manga-chapters-holder[data-id] is hardcoded on the site template; the real ID is in dataStory
        val storyId = storyIdRegex.find(document.html())?.groupValues?.get(1)
            ?: document.selectFirst("#manga-chapters-holder")?.attrOrNull("data-id")
            ?: return emptyList()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attrOrNull("content").orEmpty()

        val formBody = FormBody.Builder()
            .add("story_id", storyId)
            .add("_token", csrfToken)
            .add("routeName", "story.detail")
            .build()

        val chapterHtml = client.post(
            url = "$baseUrl/webapi/stories/list-chapter",
            headers = ajaxHeaders,
            fetch = Fetch.XHR.from(mangaUrl),
            body = formBody,
        ).parseAs<ChapterListResponseDto>().data?.html ?: return emptyList()

        return Jsoup.parseBodyFragment(chapterHtml, baseUrl)
            .select("li.wp-manga-chapter")
            .map { element ->
                SChapter.create().apply {
                    val link = element.selectFirst("a")!!
                    setUrlWithoutDomain(link.attr("href").trim())
                    name = link.text()
                    date_upload = parseChapterDate(element.selectFirst("span.chapter-release-date")?.text())
                }
            }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val match = relativeDateRegex.find(dateStr.lowercase())
        if (match != null) {
            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = when (match.groupValues[2]) {
                "giây", "second", "seconds" -> ChronoUnit.SECONDS
                "phút", "minute", "minutes" -> ChronoUnit.MINUTES
                "giờ", "hour", "hours" -> ChronoUnit.HOURS
                "ngày", "day", "days" -> ChronoUnit.DAYS
                "tuần", "week", "weeks" -> ChronoUnit.WEEKS
                "tháng", "month", "months" -> ChronoUnit.MONTHS
                "năm", "year", "years" -> ChronoUnit.YEARS
                else -> return 0L
            }
            return ZonedDateTime.now(zoneId).minus(amount, unit).toInstant().toEpochMilli()
        }
        return dateFormat.tryParseDate(dateStr, zoneId)
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return document.select("div.page-break img").mapIndexed { index, element ->
            Page(index, imageUrl = element.imgAttr())
        }
    }

    // ============================== Filters ===============================

    override val supportsFilterFetching: Boolean = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/tim-kiem", Fetch.DOCUMENT).asJsoup()
        val genres = document.select("#search-advanced input[name='genre[]']").mapNotNull { input ->
            val id = input.attrOrNull("value") ?: return@mapNotNull null
            val label = document.selectFirst("label[for='${input.id()}']")?.textOrNull() ?: return@mapNotNull null
            GenreOption(name = label, id = id)
        }
        return genres.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreOption>>().orEmpty()
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                if (genres.isNotEmpty()) {
                    add(GenreFilter(genres))
                } else {
                    add(Filter.Separator())
                    add(Filter.Header("Nhấn 'Đặt lại' để tải danh sách thể loại"))
                }
            },
        )
    }

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = attrOrNull("abs:data-src")
        ?: attrOrNull("abs:data-lazy-src")
        ?: attrOrNull("abs:data-cfsrc")
        ?: attrOrNull("abs:srcset")?.substringBefore(" ")
        ?: attr("abs:src")

    companion object {
        private val storyIdRegex = Regex("""dataStory\s*=\s*\{[^}]*"story_id"\s*:\s*(\d+)""")
        private val relativeDateRegex = Regex("""(\d+)\s*(giây|phút|giờ|ngày|tuần|tháng|năm|seconds?|minutes?|hours?|days?|weeks?|months?|years?)""")
        private val zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    }
}
