package com.flaxfer

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class FlaxferProvider : MainAPI() {
    override var mainUrl = "https://flaxfer.lol"
    override var name = "Flaxfer"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbKey = "3e20e76d6d210b6cb128d17d233b64dc"
    private val imgBase = "https://image.tmdb.org/t/p"

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )

    data class TmdbSeasonDetail(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null
    )

    data class TmdbListResponse(
        @JsonProperty("results") val results: List<TmdbItem>? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null
    )

    private fun TmdbItem.toSearchResponse(): SearchResponse? {
        val itemId = id ?: return null
        if (mediaType.equals("person", ignoreCase = true)) return null
        val itemTitle = (title ?: name)?.takeIf { it.isNotBlank() } ?: return null
        val isTv = mediaType.equals("tv", ignoreCase = true) ||
            (!mediaType.equals("movie", ignoreCase = true) && firstAirDate != null && releaseDate == null)
        val href = if (isTv) "$mainUrl/tv/$itemId" else "$mainUrl/movie/$itemId"
        val poster = posterPath?.let { "$imgBase/w500$it" }
        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(itemTitle, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbBase/trending/all/day?api_key=$tmdbKey" to "Trending Now",
        "$tmdbBase/movie/popular?api_key=$tmdbKey" to "Popular Movies",
        "$tmdbBase/tv/popular?api_key=$tmdbKey" to "Popular TV Shows",
        "$tmdbBase/movie/top_rated?api_key=$tmdbKey" to "Top Rated Movies",
        "$tmdbBase/discover/tv?api_key=$tmdbKey&with_genres=16&with_original_language=ja" to "Anime & Animation",
        "$tmdbBase/discover/tv?api_key=$tmdbKey&with_original_language=ko&sort_by=popularity.desc" to "K-Drama",
        "$tmdbBase/discover/movie?api_key=$tmdbKey&with_original_language=hi&sort_by=popularity.desc" to "Bollywood"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val res = app.get(url).parsedSafe<TmdbListResponse>()
        val items = res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val hasNext = (res?.page ?: 1) < (res?.totalPages ?: 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$tmdbBase/search/multi?api_key=$tmdbKey&query=$encoded&include_adult=false&page=1"
        val res = app.get(url).parsedSafe<TmdbListResponse>()
        return res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.substringBefore("?").trimEnd('/')
        val isTv = clean.contains("/tv/")
        val slugOrId = clean.substringAfterLast("/")
        val id = Regex("(\\d+)$").find(slugOrId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null

        return if (isTv) {
            val data = app.get("$tmdbBase/tv/$id?api_key=$tmdbKey").parsedSafe<TmdbItem>() ?: return null
            val title = (data.name ?: data.title) ?: return null
            val episodes = mutableListOf<Episode>()

            data.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.forEach { season ->
                val sNum = season.seasonNumber ?: 1
                val seasonDetail = app.get("$tmdbBase/tv/$id/season/$sNum?api_key=$tmdbKey")
                    .parsedSafe<TmdbSeasonDetail>()
                val epList = seasonDetail?.episodes
                if (!epList.isNullOrEmpty()) {
                    epList.forEach { ep ->
                        val eNum = ep.episodeNumber ?: return@forEach
                        episodes.add(
                            newEpisode("$id|$sNum|$eNum") {
                                this.name = ep.name?.takeIf { it.isNotBlank() } ?: "Episode $eNum"
                                this.season = sNum
                                this.episode = eNum
                                this.description = ep.overview
                                this.posterUrl = (ep.stillPath ?: data.posterPath)?.let { "$imgBase/w500$it" }
                            }
                        )
                    }
                } else {
                    val count = season.episodeCount ?: 0
                    for (eNum in 1..count) {
                        episodes.add(
                            newEpisode("$id|$sNum|$eNum") {
                                this.name = "Episode $eNum"
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = data.posterPath?.let { "$imgBase/w500$it" }
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
                this.posterUrl = data.posterPath?.let { "$imgBase/w500$it" }
                this.backgroundPosterUrl = data.backdropPath?.let { "$imgBase/original$it" }
                this.plot = data.overview
                this.year = data.firstAirDate?.take(4)?.toIntOrNull()
                this.score = Score.from10(data.voteAverage)
            }
        } else {
            val data = app.get("$tmdbBase/movie/$id?api_key=$tmdbKey").parsedSafe<TmdbItem>() ?: return null
            val title = (data.title ?: data.name) ?: return null
            newMovieLoadResponse(title, "$mainUrl/movie/$id", TvType.Movie, "$id") {
                this.posterUrl = data.posterPath?.let { "$imgBase/w500$it" }
                this.backgroundPosterUrl = data.backdropPath?.let { "$imgBase/original$it" }
                this.plot = data.overview
                this.year = data.releaseDate?.take(4)?.toIntOrNull()
                this.score = Score.from10(data.voteAverage)
            }
        }
    }

    private fun extractUrlsRecursive(
        value: Any?,
        out: MutableList<Triple<String, String?, String?>>,
        hintLabel: String,
        hintQuality: String?
    ) {
        if (value == null) return
        when (value) {
            is String -> {
                val txt = value.trim()
                if (txt.startsWith("http") &&
                    (txt.contains(".m3u8", ignoreCase = true) ||
                        txt.contains(".mp4", ignoreCase = true) ||
                        txt.contains("/hls/", ignoreCase = true) ||
                        txt.contains("stream", ignoreCase = true) ||
                        txt.contains("proxy", ignoreCase = true)) &&
                    !txt.contains("image.tmdb.org", ignoreCase = true) &&
                    !txt.endsWith(".jpg", ignoreCase = true) &&
                    !txt.endsWith(".png", ignoreCase = true) &&
                    !txt.endsWith(".webp", ignoreCase = true)
                ) {
                    out.add(Triple(txt, hintLabel, hintQuality))
                }
            }
            is List<*> -> {
                for (item in value) {
                    extractUrlsRecursive(item, out, hintLabel, hintQuality)
                }
            }
            is Map<*, *> -> {
                val label = (value["provider"] ?: value["server"])?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: hintLabel
                val quality = (value["quality"] ?: value["resolution"])?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: hintQuality

                val priorityKeys = listOf(
                    "proxied_url", "download_url", "manifestUrl", "manifest_url",
                    "streamUrl", "stream_url", "directPlayUrl", "url", "src",
                    "file", "video", "video_url", "link", "source"
                )
                for (k in priorityKeys) {
                    val v = value[k]
                    if (v is String && v.trim().startsWith("http") && !v.contains("image.tmdb.org", ignoreCase = true)) {
                        out.add(Triple(v.trim(), label, quality))
                    }
                }

                for ((k, v) in value) {
                    val keyStr = k?.toString()?.lowercase() ?: ""
                    if (keyStr !in listOf("poster", "backdrop", "image", "thumbnail", "subtitle", "subtitles", "subs", "captions")) {
                        extractUrlsRecursive(v, out, label, quality)
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val isTv = parts.size >= 3
        val id = parts[0]
        val s = parts.getOrNull(1) ?: "1"
        val e = parts.getOrNull(2) ?: "1"

        val seenUrls = mutableSetOf<String>()
        var addedCount = 0

        suspend fun emitStream(rawUrl: String?, sourceLabel: String, qualityStr: String?) {
            val cleanUrl = rawUrl?.trim()?.takeIf { it.startsWith("http") } ?: return
            if (!seenUrls.add(cleanUrl)) return

            val qInt = Regex("(\\d{3,4})").find(qualityStr ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val isHls = cleanUrl.contains(".m3u8", ignoreCase = true) ||
                cleanUrl.contains(".m3u", ignoreCase = true) ||
                cleanUrl.contains("/hls/", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = cleanUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qInt
                }
            )
            addedCount++
        }

        val endpoints = if (isTv) {
            listOf(
                "Astra" to "https://astra0.thatis3word.dev/api/resolve?type=tv&id=$id&s=$s&e=$e",
                "CineBros" to "https://altra0.forilas852.workers.dev/cinebros/api/tv/$id/$s/$e",
                "VZ Cloud" to "https://api.vz.raphsm4.dev/resolve?id=$id&type=tv&season=$s&episode=$e",
                "MVSNerd" to "https://mvsnerd.namitutu5.workers.dev/tv/$id/$s/$e",
                "MVSNerd 2" to "https://plain-sun-7323.karloswnso.workers.dev/tv/$id/$s/$e",
                "VidSpark" to "https://alexamuf.xyz/vs/tv/$id/$s/$e",
                "MovieBox" to "https://themoviebox.uk/download/tv/$id/$s/$e",
                "Flix2Watch" to "https://flix2watch.pro/api/tv/$id/$s/$e"
            )
        } else {
            listOf(
                "Astra" to "https://astra0.thatis3word.dev/api/resolve?type=movie&id=$id",
                "CineBros" to "https://altra0.forilas852.workers.dev/cinebros/api/movie/$id",
                "VZ Cloud" to "https://api.vz.raphsm4.dev/resolve?id=$id&type=movie",
                "MVSNerd" to "https://mvsnerd.namitutu5.workers.dev/movie/$id",
                "MVSNerd 2" to "https://plain-sun-7323.karloswnso.workers.dev/movie/$id",
                "VidSpark" to "https://alexamuf.xyz/vs/movie/$id",
                "MovieBox" to "https://themoviebox.uk/download/movie/$id",
                "Flix2Watch" to "https://flix2watch.pro/api/movie/$id"
            )
        }

        endpoints.amap { (providerLabel, apiUrl) ->
            try {
                val rawText = app.get(apiUrl, timeout = 12).text
                if (rawText.isBlank()) return@amap

                val parsedMap = try {
                    AppUtils.parseJson>(rawText)
                } catch (_: Exception) {
                    null
                }

                if (parsedMap != null) {
                    val found = mutableListOf<Triple<String, String?, String?>>()
                    extractUrlsRecursive(parsedMap, found, providerLabel, null)
                    found.forEach { (u, lbl, q) ->
                        emitStream(u, "$name - ${lbl ?: providerLabel}", q)
                    }

                    val subsList = parsedMap["subtitles"] as? List<*>
                    subsList?.forEach { subAny ->
                        val subMap = subAny as? Map<*, *> ?: return@forEach
                        val subUrl = subMap["url"]?.toString()?.trim().orEmpty()
                        if (subUrl.startsWith("http")) {
                            val subLabel = (subMap["label"] ?: subMap["language"] ?: subMap["lang"])
                                ?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "English"
                            subtitleCallback.invoke(newSubtitleFile(subLabel, subUrl))
                        }
                    }
                } else {
                    Regex("https?://[^\\s\"'<>\\\\]+\\.(?:m3u8|mp4)(?:\\?[^\\s\"'<>\\\\]*)?", RegexOption.IGNORE_CASE)
                        .findAll(rawText)
                        .forEach { match ->
                            emitStream(match.value, "$name - $providerLabel", null)
                        }
                }
            } catch (_: Exception) {
            }
        }

        val embedTargets = if (isTv) {
            listOf(
                "https://vidlink.pro/tv/$id/$s/$e",
                "https://vixsrc.to/tv/$id/$s/$e/",
                "https://freemoviesstreaming.site/embed/tv/$id/$s/$e",
                "https://flaxmovies.xyz/embed/tv/$id/$s/$e"
            )
        } else {
            listOf(
                "https://vidlink.pro/movie/$id",
                "https://vixsrc.to/movie/$id",
                "https://freemoviesstreaming.site/embed/movie/$id",
                "https://flaxmovies.xyz/embed/movie/$id"
            )
        }

        embedTargets.amap { embedUrl ->
            try {
                if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) {
                    addedCount++
                }
            } catch (_: Exception) {
            }
        }

        return addedCount > 0
    }
}
