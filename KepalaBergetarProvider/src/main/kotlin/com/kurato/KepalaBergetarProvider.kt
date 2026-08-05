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
}
