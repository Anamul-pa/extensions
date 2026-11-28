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
                            newEpisode("$id|$sNum\vert{}$eNum") {
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
                            newEpisode("$id|$sNum\vert{}$eNum") {
                                this.name = "Episode $eNum"
                                this.season = sNum
                                this.episode = eNum
                                this
