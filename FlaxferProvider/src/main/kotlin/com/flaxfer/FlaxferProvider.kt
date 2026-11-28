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

    data class DirectStreamItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("proxied_url") val proxiedUrl: String? = null,
        @JsonProperty("download_url") val downloadUrl: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class DirectSubtitleItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    data class DirectResolveResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("streams") val streams: List<DirectStreamItem>? = null,
        @JsonProperty("sources") val sources: List<DirectStreamItem>? = null,
        @JsonProperty("downloads") val downloads: List<DirectStreamItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<DirectSubtitleItem>? = null,
        @JsonProperty("url") val url: String? = null
    )

    private fun TmdbItem.toSearchResponse(): SearchResponse? {
        val itemId = id ?: return null
        if (mediaType.equals("person", ignoreCase = true)) return null
        val itemTitle = (title ?: name)?.takeIf { it.isNotBlank() } ?: return null
        val isTv = mediaType.equals("tv", ignoreCase = true) || (!mediaType.equals("movie", ignoreCase = true) && firstAirDate != null && releaseDate == null)
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

        suspend fun emitDirectLink(rawUrl: String?, sourceLabel: String, qualityStr: String?, formatStr: String?) {
            val cleanUrl = rawUrl?.trim()?.takeIf { it.startsWith("http") } ?: return
            if (!seenUrls.add(cleanUrl)) return

            val qInt = Regex("(\\d{3,4})").find(qualityStr ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val isHls = formatStr.equals("hls", ignoreCase = true) ||
                cleanUrl.contains(".m3u8", ignoreCase = true) ||
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

        val directEndpoints = if (isTv) {
            listOf(
                "Astra" to "https://astra0.thatis3word.dev/api/resolve?type=tv&id=$id&s=$s&e=$e",
                "CineBros" to "https://altra0.forilas852.workers.dev/cinebros/api/tv/$id/$s/$e",
                "VZ Cloud" to "https://api.vz.raphsm4.dev/resolve?id=$id&type=tv&season=$s&episode=$e",
                "MVSNerd" to "https://mvsnerd.namitutu5.workers.dev/tv/$id/$s/$e",
                "VidSpark" to "https://alexamuf.xyz/vs/tv/$id/$s/$e",
                "MovieBox MP4" to "https://themoviebox.uk/download/tv/$id/$s/$e",
                "Flix2Watch" to "https://flix2watch.pro/api/tv/$id/$s/$e"
            )
        } else {
            listOf(
                "Astra" to "https://astra0.thatis3word.dev/api/resolve?type=movie&id=$id",
                "CineBros" to "https://altra0.forilas852.workers.dev/cinebros/api/movie/$id",
                "VZ Cloud" to "https://api.vz.raphsm4.dev/resolve?id=$id&type=movie",
                "MVSNerd" to "https://mvsnerd.namitutu5.workers.dev/movie/$id",
                "VidSpark" to "https://alexamuf.xyz/vs/movie/$id",
                "MovieBox MP4" to "https://themoviebox.uk/download/movie/$id",
                "Flix2Watch" to "https://flix2watch.pro/api/movie/$id"
            )
        }

        directEndpoints.amap { (providerLabel, apiUrl) ->
            try {
                val res = app.get(apiUrl, timeout = 12).parsedSafe<DirectResolveResponse>() ?: return@amap
                res.subtitles?.forEach { sub ->
                    val subUrl = sub.url?.takeIf { it.startsWith("http") } ?: return@forEach
                    val subName = sub.label ?: sub.language ?: sub.lang ?: "English"
                    subtitleCallback.invoke(SubtitleFile(subName, subUrl))
                }
                val combined = (res.streams ?: emptyList()) + (res.sources ?: emptyList()) + (res.downloads ?: emptyList())
                combined.forEach { item ->
                    val finalUrl = item.proxiedUrl ?: item.downloadUrl ?: item.url
                    val label = item.provider?.takeIf { it.isNotBlank() } ?: providerLabel
                    emitDirectLink(finalUrl, "$name - $label", item.quality ?: item.resolution, item.format ?: item.type)
                }
                if (!res.url.isNullOrBlank()) {
                    emitDirectLink(res.url, "$name - $providerLabel", "HD", null)
                }
            } catch (_: Exception) {
            }
        }

        val embedUrls = if (isTv) {
            listOf(
                "https://vidlink.pro/tv/$id/$s/$e",
                "https://vixsrc.to/tv/$id/$s/$e/"
            )
        } else {
            listOf(
                "https://vidlink.pro/movie/$id",
                "https://vixsrc.to/movie/$id"
            )
        }

        embedUrls.amap { embedUrl ->
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
