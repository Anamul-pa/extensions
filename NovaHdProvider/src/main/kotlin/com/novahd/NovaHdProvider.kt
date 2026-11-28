package com.novahd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

class NovaHdProvider : MainAPI() {
    override var mainUrl = "https://novahd.cc"
    override var name = "NovaHD"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val visitorId = UUID.randomUUID().toString()
    private val imageBase = "https://image.tmdb.org/t/p"

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "x-nova-visitor" to visitorId,
        "X-Nova-Visitor" to visitorId,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    private fun posterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$imageBase/w500$path"
    }

    private fun backdropUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$imageBase/w1280$path"
    }

    data class NovaTitleItem(
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("seasons") val seasons: List<NovaSeason>? = null
    )

    data class NovaSeason(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episodes") val episodes: List<NovaEpisode>? = null
    )

    data class NovaEpisode(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("airDate") val airDate: String? = null
    )

    data class NovaListResponse(
        @JsonProperty("results") val results: List<NovaTitleItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null
    )

    data class NovaSourceItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class NovaSourcesResponse(
        @JsonProperty("sources") val sources: List<NovaSourceItem>? = null
    )

    private fun NovaTitleItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val itemTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val isMovie = type.equals("movie", ignoreCase = true) || (!type.equals("tv", ignoreCase = true) && !type.equals("show", ignoreCase = true) && releaseDate != null && firstAirDate == null)
        val href = if (isMovie) "$mainUrl/m/$id" else "$mainUrl/s/$id"
        return if (isMovie) {
            newMovieSearchResponse(itemTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl(posterPath)
            }
        } else {
            newTvSeriesSearchResponse(itemTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl(posterPath)
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/trending?type=all" to "Trending Now",
        "$mainUrl/api/movies?sort=popularity&limit=18&count=0" to "Popular Movies",
        "$mainUrl/api/shows?sort=voteAverage&limit=18&count=0" to "Top Rated Series",
        "$mainUrl/api/movies?sort=releaseDate&limit=18&count=0" to "New Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page > 1 && request.data.contains("page=")) {
            request.data.replace(Regex("page=\\d+"), "page=$page")
        } else if (page > 1 && !request.data.contains("/api/trending")) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val res = app.get(targetUrl, headers = defaultHeaders(), interceptor = cloudflareKiller)
            .parsedSafe<NovaListResponse>()
        val items = res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && !request.data.contains("/api/trending"))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/search?search=$encoded&page=1"
        val res = app.get(url, headers = defaultHeaders(), interceptor = cloudflareKiller)
            .parsedSafe<NovaListResponse>()
        return res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.substringBefore("?").trimEnd('/')
        val isMovie = clean.contains("/m/")
        val id = clean.substringAfterLast("/").toIntOrNull() ?: return null

        return if (isMovie) {
            val detailUrl = "$mainUrl/api/movies/$id"
            val item = app.get(detailUrl, headers = defaultHeaders(), interceptor = cloudflareKiller)
                .parsedSafe<NovaTitleItem>() ?: return null
            val title = item.title ?: return null
            newMovieLoadResponse(title, "$mainUrl/m/$id", TvType.Movie, "$id") {
                this.posterUrl = posterUrl(item.posterPath)
                this.backgroundPosterUrl = backdropUrl(item.backdropPath)
                this.plot = item.overview
                this.year = item.releaseDate?.take(4)?.toIntOrNull()
                this.rating = item.voteAverage?.let { ((it * 10).toInt()) }
            }
        } else {
            val detailUrl = "$mainUrl/api/shows/$id"
            val item = app.get(detailUrl, headers = defaultHeaders(), interceptor = cloudflareKiller)
                .parsedSafe<NovaTitleItem>() ?: return null
            val title = item.title ?: return null
            val episodes = mutableListOf<Episode>()
            item.seasons?.forEach { season ->
                val sNum = season.seasonNumber ?: 1
                season.episodes?.forEach { ep ->
                    val eNum = ep.episodeNumber ?: return@forEach
                    episodes.add(
                        newEpisode("$id|$sNum|$eNum") {
                            this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $eNum"
                            this.season = sNum
                            this.episode = eNum
                            this.description = ep.overview
                            this.posterUrl = posterUrl(ep.stillPath ?: item.posterPath)
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, "$mainUrl/s/$id", TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl(item.posterPath)
                this.backgroundPosterUrl = backdropUrl(item.backdropPath)
                this.plot = item.overview
                this.year = item.firstAirDate?.take(4)?.toIntOrNull()
                this.rating = item.voteAverage?.let { ((it * 10).toInt()) }
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
        val apiUrl = if (parts.size >= 3) {
            val tmdbId = parts[0]
            val season = parts[1]
            val episode = parts[2]
            "$mainUrl/api/sources?type=show&tmdbId=$tmdbId&season=$season&episode=$episode"
        } else {
            val tmdbId = parts[0]
            "$mainUrl/api/sources?type=movie&tmdbId=$tmdbId"
        }

        val responseText = app.get(apiUrl, headers = defaultHeaders(), interceptor = cloudflareKiller).text
        val parsed = try {
            AppUtils.parseJson<NovaSourcesResponse>(responseText)
        } catch (_: Exception) {
            null
        }

        val foundSources = parsed?.sources ?: emptyList()
        var added = 0
        foundSources.forEach { src ->
            val streamUrl = src.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val label = buildString {
                append(src.provider?.takeIf { it.isNotBlank() } ?: name)
                if (!src.language.isNullOrBlank()) append(" (${src.language})")
            }
            val qInt = Regex("(\\d{3,4})").find(src.quality ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val isHls = src.type.equals("hls", ignoreCase = true) || streamUrl.contains(".m3u8", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qInt
                }
            )
            added++
        }
        return added > 0
    }
}
