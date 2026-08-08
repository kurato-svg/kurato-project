package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.ArrayList

class KissKH : MainAPI() {

    override var mainUrl = "https://kisskh.ovh"
    override var name = "KissKH"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    private val domains = listOf(
        "https://kisskh.ovh",
        "https://kisskh.do",
        "https://kisskh.co",
        "https://kisskh.id",
        "https://kisskh.la"
    )

    private var resolvedDomain: String? = null

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Latest Releases",
        "&type=0&sub=0&country=2&status=0&order=1" to "Best Korean Dramas",
        "&type=0&sub=0&country=1&status=0&order=1" to "Best Chinese Dramas",
        "&type=2&sub=0&country=2&status=0&order=1" to "Popular Movies",
        "&type=2&sub=0&country=2&status=0&order=2" to "Latest Updated Movies",
        "&type=1&sub=0&country=2&status=0&order=1" to "Popular TV Series",
        "&type=1&sub=0&country=2&status=0&order=2" to "Latest Updated TV Series",
        "&type=3&sub=0&country=0&status=0&order=1" to "Popular Anime",
        "&type=3&sub=0&country=0&status=0&order=2" to "Latest Updated Anime",
        "&type=4&sub=0&country=0&status=0&order=1" to "Popular Hollywood",
        "&type=4&sub=0&country=0&status=0&order=2" to "Latest Updated Hollywood",
        "&type=0&sub=0&country=0&status=3&order=2" to "Coming Soon"
    )

    private suspend fun ensureDomain(): String {
        resolvedDomain?.let {
            mainUrl = it
            return it
        }

        val probePath =
            "/api/DramaList/List?page=1&type=0&sub=0&country=0&status=0&order=2&pageSize=1"

        for (domain in domains) {
            try {
                Log.d(TAG, "Checking domain: $domain")

                val response = app.get(
                    "$domain$probePath",
                    referer = "$domain/",
                    timeout = 4000
                ).parsedSafe<Responses>()

                if (response?.data != null) {
                    mainUrl = domain
                    resolvedDomain = domain

                    Log.d(TAG, "Using domain: $domain")
                    return domain
                }
            } catch (e: Exception) {
                Log.d(TAG, "Domain failed: $domain | ${e.message}")
            }
        }

        throw ErrorLoadingException("No working KissKH domain found")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val base = ensureDomain()

        val response = app.get(
            "$base/api/DramaList/List?page=$page${request.data}&pageSize=$PAGE_SIZE",
            referer = "$base/"
        ).parsedSafe<Responses>()
            ?: throw ErrorLoadingException("Invalid KissKH response")

        val home = response.data
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.size >= PAGE_SIZE
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {

        if (
            !settingsForProvider.enableAdult &&
            label?.contains("RAW", ignoreCase = true) == true
        ) {
            return null
        }

        val mediaTitle = title ?: return null
        val mediaId = id ?: return null

        return newAnimeSearchResponse(
            mediaTitle,
            "${getTitle(mediaTitle)}/$mediaId",
            TvType.TvSeries
        ) {
            posterUrl = thumbnail

            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT
            )

            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val base = ensureDomain()

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val response = app.get(
            "$base/api/DramaList/Search?q=$encodedQuery&type=0",
            referer = "$base/"
        ).text

        return tryParseJson<ArrayList<Media>>(response)
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {

        val base = ensureDomain()

        val dramaId = url.substringAfterLast("/")
        val slug = url.substringBeforeLast("/")
            .substringAfterLast("/")

        val res = app.get(
            "$base/api/DramaList/Drama/$dramaId?isq=false",
            referer = "$base/Drama/$slug?id=$dramaId"
        ).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid drama response")

        val episodes = res.episodes
            ?.mapNotNull { eps ->

                val epsId = eps.id ?: return@mapNotNull null

                val displayNumber = formatEpisodeNumber(eps.number)

                newEpisode(
                    Data(
                        title = res.title,
                        eps = eps.number,
                        id = res.id,
                        epsId = epsId
                    ).toJson()
                ) {
                    name = "Episode $displayNumber"
                }
            }
            ?: throw ErrorLoadingException("No episodes found")

        val isMovie =
            res.type?.contains("Movie", ignoreCase = true) == true ||
            episodes.size == 1

        return newTvSeriesLoadResponse(
            res.title ?: return null,
            url,
            if (isMovie) TvType.Movie else TvType.TvSeries,
            episodes.reversed()
        ) {

            posterUrl = res.thumbnail?.trim()

            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$base/"
            )

            year = res.releaseDate
                ?.substringBefore("-")
                ?.toIntOrNull()

            plot = res.description

            tags = listOfNotNull(
                res.country?.takeIf { it.isNotBlank() },
                res.status?.takeIf { it.isNotBlank() },
                res.type?.takeIf { it.isNotBlank() }
            )

            showStatus = when {
                res.status?.contains(
                    "Completed",
                    ignoreCase = true
                ) == true -> ShowStatus.Completed

                res.status?.contains(
                    "Ongoing",
                    ignoreCase = true
                ) == true -> ShowStatus.Ongoing

                else -> null
            }
        }
    }

    private fun formatEpisodeNumber(number: Double?): String {
        if (number == null) return ""

        return if (number % 1.0 == 0.0) {
            number.toInt().toString()
        } else {
            number.toString()
        }
    }

    private fun getTitle(str: String): String {
        return str
            .replace(Regex("[^a-zA-Z0-9]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun getLanguage(str: String): String {
        return when (str.trim()) {
            "Indonesia" -> "Indonesian"
            else -> str.trim()
        }
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("1080", ignoreCase = true) ->
                Qualities.P1080.value

            url.contains("720", ignoreCase = true) ->
                Qualities.P720.value

            else ->
                Qualities.Unknown.value
        }
    }

    private suspend fun getKey(
        keyApi: String,
        episodeId: Int,
        type: String
    ): String {

        return try {
            val url =
                "$keyApi$episodeId&version=$KISSKH_VERSION"

            Log.d(TAG, "Requesting $type key")

            app.get(
                url,
                timeout = 8000
            ).parsedSafe<Key>()
                ?.key
                ?.trim()
                .orEmpty()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to get $type key: ${e.message}"
            )

            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val base = ensureDomain()

        val loadData =
            tryParseJson<Data>(data)
                ?: return false

        val episodeId =
            loadData.epsId
                ?: return false

        Log.d(
            TAG,
            "loadLinks episodeId=$episodeId"
        )

        /*
         * Get both KissKH keys.
         */
        val keys = listOf(
            VIDEO_KEY_API to "video",
            SUBTITLE_KEY_API to "subtitle"
        ).amap { (api, type) ->
            getKey(
                api,
                episodeId,
                type
            )
        }

        val videoKey =
            keys.getOrNull(0).orEmpty()

        val subtitleKey =
            keys.getOrNull(1).orEmpty()

        /*
         * VIDEO
         */
        var sourceFound = false

        if (videoKey.isNotBlank()) {

            val encodedVideoKey =
                URLEncoder.encode(
                    videoKey,
                    "UTF-8"
                )

            val episodeNumber =
                formatEpisodeNumber(loadData.eps)

            val dramaSlug =
                getTitle(
                    loadData.title.orEmpty()
                )

            val videoApiUrl =
                "$base/api/DramaList/Episode/$episodeId.png" +
                    "?err=false&ts=&time=&kkey=$encodedVideoKey"

            val videoReferer =
                "$base/Drama/$dramaSlug/Episode-$episodeNumber" +
                    "?id=${loadData.id}" +
                    "&ep=$episodeId" +
                    "&page=0" +
                    "&pageSize=100"

            val source = try {
                app.get(
                    videoApiUrl,
                    referer = videoReferer,
                    headers = mapOf(
                        "Origin" to base,
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 10000
                ).parsedSafe<Sources>()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Video API failed: ${e.message}"
                )
                null
            }

            if (source != null) {

                Log.d(
                    TAG,
                    "Video=${source.video}"
                )

                Log.d(
                    TAG,
                    "ThirdParty=${source.thirdParty}"
                )

                val links = listOfNotNull(
                    source.video
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },

                    source.thirdParty
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                ).distinct()

                sourceFound = links.isNotEmpty()

                links.amap { rawLink ->

                    safeApiCall {

                        val link = rawLink.trim()

                        when {

                            link.contains(
                                ".m3u8",
                                ignoreCase = true
                            ) -> {

                                Log.d(
                                    TAG,
                                    "M3U8: $link"
                                )

                                M3u8Helper.generateM3u8(
                                    name,
                                    fixUrl(link),
                                    referer = "$base/",
                                    headers = mapOf(
                                        "Origin" to base,
                                        "Referer" to "$base/",
                                        "User-Agent" to USER_AGENT
                                    )
                                ).forEach(callback)
                            }

                            link.contains(
                                ".mp4",
                                ignoreCase = true
                            ) -> {

                                Log.d(
                                    TAG,
                                    "MP4: $link"
                                )

                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        name,
                                        url = fixUrl(link),
                                        INFER_TYPE
                                    ) {
                                        referer = "$base/"
                                        quality =
                                            inferQuality(link)

                                        headers = mapOf(
                                            "Origin" to base,
                                            "Referer" to "$base/",
                                            "User-Agent" to USER_AGENT
                                        )
                                    }
                                )
                            }

                            link.startsWith(
                                "http",
                                ignoreCase = true
                            ) -> {

                                Log.d(
                                    TAG,
                                    "Extractor: $link"
                                )

                                /*
                                 * Do not use substringBefore("=http").
                                 * Pass the original URL to the extractor.
                                 */
                                loadExtractor(
                                    link,
                                    "$base/",
                                    subtitleCallback,
                                    callback
                                )
                            }

                            else -> {
                                Log.d(
                                    TAG,
                                    "Unknown stream format: $link"
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Log.e(
                TAG,
                "Video kkey is empty"
            )
        }

        /*
         * SUBTITLES
         */
        var subtitleFound = false

        if (subtitleKey.isNotBlank()) {

            val encodedSubtitleKey =
                URLEncoder.encode(
                    subtitleKey,
                    "UTF-8"
                )

            val subtitleUrl =
                "$base/api/Sub/$episodeId?kkey=$encodedSubtitleKey"

            try {

                val subtitleResponse = app.get(
                    subtitleUrl,
                    referer = "$base/",
                    headers = mapOf(
                        "Origin" to base,
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 10000
                ).text

                val subtitles =
                    tryParseJson<List<Subtitle>>(
                        subtitleResponse
                    ).orEmpty()

                subtitles.forEach { sub ->

                    val src =
                        sub.src
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: return@forEach

                    val language =
                        getLanguage(
                            sub.label
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "Unknown"
                        )

                    Log.d(
                        TAG,
                        "Subtitle: $language | $src"
                    )

                    subtitleCallback.invoke(
                        newSubtitleFile(
                            language,
                            fixUrl(src)
                        )
                    )

                    subtitleFound = true
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Subtitle API failed: ${e.message}"
                )
            }

        } else {

            Log.e(
                TAG,
                "Subtitle kkey is empty"
            )
        }

        return sourceFound || subtitleFound
    }

    /*
     * KissKH encrypted .txt subtitles.
     */
    private val CHUNK_REGEX by lazy {
        Regex(
            "^\\d+$",
            RegexOption.MULTILINE
        )
    }

    override fun getVideoInterceptor(
        extractorLink: ExtractorLink
    ): Interceptor {

        return object : Interceptor {

            override fun intercept(
                chain: Interceptor.Chain
            ): Response {

                val request =
                    chain.request()
                        .newBuilder()
                        .build()

                val response =
                    chain.proceed(request)

                val url =
                    response.request.url
                        .toString()

                if (
                    !url.contains(
                        ".txt",
                        ignoreCase = true
                    )
                ) {
                    return response
                }

                Log.d(
                    "KISSKH_SUB",
                    "Decrypting subtitle: $url"
                )

                val body =
                    response.body

                val contentType =
                    body.contentType()

                val encryptedSubtitle =
                    body.string()

                val chunks =
                    encryptedSubtitle
                        .split(CHUNK_REGEX)
                        .filter {
                            it.isNotBlank()
                        }
                        .map {
                            it.trim()
                        }

                val decryptedSubtitle =
                    chunks.mapIndexedNotNull {
                            index,
                            chunk ->

                        val parts =
                            chunk.split("\n")

                  
