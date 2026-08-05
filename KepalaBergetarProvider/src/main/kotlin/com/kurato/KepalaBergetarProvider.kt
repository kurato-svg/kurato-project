package com.kurato

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class KepalaBergetarProvider : MainAPI() {

    override var mainUrl = "https://kepalabergetar9.pro"
    override var name = "KepalaBergetar"

    override var lang = "ms"

    override val hasMainPage = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Recent Posts",
        "/category/astro-ria-episod/" to "Astro Ria",
        "/category/tv3-episode/" to "TV3"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            mainUrl
        } else {
            "$mainUrl/page/$page/"
        }

        val document = app.get(url).document

        val items = document.select("h3 a[href]").mapNotNull { linkElement ->
            val link = linkElement.attr("href").trim()
            val title = linkElement.text().trim()

            if (link.isBlank() || title.isBlank()) {
                return@mapNotNull null
            }

            newMovieSearchResponse(
                title,
                link,
                TvType.TvSeries
            )
        }

        val hasNext = document.selectFirst(
            "a.next, .next.page-numbers, a[rel=next]"
        ) != null

        return newHomePageResponse(
            request.name,
            items,
            hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document

    val title = document.selectFirst(
        "h1.entry-title, h1.post-title, h1"
    )?.text()?.trim()
        ?: "KepalaBergetar"

    val poster = document.selectFirst(
        "meta[property=og:image]"
    )?.attr("content")
        ?: document.selectFirst("article img, .post img, img")?.attr("src")

    val description = document.selectFirst(
        "meta[property=og:description]"
    )?.attr("content")
        ?: document.selectFirst(
            "article p, .entry-content p, .post-content p"
        )?.text()?.trim()

    val iframeUrl = document.selectFirst("iframe[src]")
        ?.attr("src")
        ?.trim()

    return newMovieLoadResponse(
        title,
        url,
        TvType.Movie,
        url
    ) {
        this.posterUrl = poster
        this.plot = description

        if (!iframeUrl.isNullOrBlank()) {
            addTrailer(iframeUrl)
        }
    }
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val document = app.get(data).document

    val iframeUrl = document.selectFirst("iframe[src]")
        ?.attr("src")
        ?.trim()
        ?: return false

    loadExtractor(
        iframeUrl,
        data,
        subtitleCallback,
        callback
    )

    return true
    }
    }
