package com.desidubanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class DesiDubAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.desidubanime.me"
    override var name = "DesiDubAnime"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/html, */*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    data class WpRendered(val rendered: String? = null)
    data class WpAnimeItem(
        val id: Int? = null,
        val link: String? = null,
        val title: WpRendered? = null,
        val content: WpRendered? = null,
        val jetpack_featured_media_url: String? = null
    )

    override val mainPage = mainPageOf(
        "latest" to "Latest & Trending",
        "popular" to "Most Popular",
        "wp_api" to "All Anime List"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = when (request.data) {
            "latest" -> if (page == 1) "$mainUrl/" else "$mainUrl/search/?s_orderby=updated&s_order=desc&s_paged=$page"
            "popular" -> "$mainUrl/search/?s_orderby=popular&s_paged=$page"
            else -> "$mainUrl/search/?s_paged=$page"
        }

        val webRes = app.get(pageUrl, headers = headers)
        if (webRes.code == 200) {
            val cards = webRes.document.select("article.anime-card").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
            if (cards.isNotEmpty()) {
                return newHomePageResponse(request.name, cards)
            }
        }

        val apiEndpoints = listOf(
            "$mainUrl/wp-json/wp/v2/anime?per_page=20&page=$page",
            "$mainUrl/wp-json/wp/v2/posts?per_page=20&page=$page"
        )

        for (apiUrl in apiEndpoints) {
            val apiRes = app.get(apiUrl, headers = headers)
            if (apiRes.code == 200 && apiRes.text.trim().startsWith("[")) {
                val items = try {
                    parseJson<List<WpAnimeItem>>(apiRes.text)
                } catch (e: Exception) {
                    emptyList()
                }
                if (items.isNotEmpty()) {
                    val mapped = items.mapNotNull { item ->
                        val rawTitle = item.title?.rendered ?: return@mapNotNull null
                        val cleanTitle = org.jsoup.Jsoup.parse(rawTitle).text().trim()
                        val itemUrl = item.link ?: return@mapNotNull null
                        newAnimeSearchResponse(cleanTitle, fixUrl(itemUrl), TvType.Anime) {
                            this.posterUrl = fixUrlNull(item.jetpack_featured_media_url)
                        }
                    }
                    if (mapped.isNotEmpty()) {
                        return newHomePageResponse(request.name, mapped)
                    }
                }
            }
        }

        return newHomePageResponse(
            request.name,
            listOf(
                newAnimeSearchResponse(
                    "BLOCKED [${webRes.code}]: Site requires browser cookie",
                    mainUrl,
                    TvType.Anime
                )
            )
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h3 a span:not([class*='jp'])") ?: this.selectFirst("h3 a")
        val title = titleEl?.text()?.trim() ?: return null

        val infoOnclick = this.selectFirst("button[onclick*='/anime/']")?.attr("onclick")
        val animeUrlFromBtn = infoOnclick?.let {
            Regex("""window\.location\.href=['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1)
        }
        val rawHref = animeUrlFromBtn ?: this.selectFirst("h3 a, a")?.attr("href") ?: return null
        val href = fixUrl(rawHref)

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("src")?.ifEmpty { img.attr("data-src") }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val webRes = app.get("$mainUrl/search/?s_keyword=$query", headers = headers)
        if (webRes.code == 200) {
            val cards = webRes.document.select("article.anime-card").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
            if (cards.isNotEmpty()) return cards
        }

        val apiRes = app.get("$mainUrl/wp-json/wp/v2/anime?search=$query&per_page=20", headers = headers)
        if (apiRes.code == 200 && apiRes.text.trim().startsWith("[")) {
            return try {
                parseJson<List<WpAnimeItem>>(apiRes.text).mapNotNull { item ->
                    val cleanTitle = org.jsoup.Jsoup.parse(item.title?.rendered ?: return@mapNotNull null).text().trim()
                    val itemUrl = item.link ?: return@mapNotNull null
                    newAnimeSearchResponse(cleanTitle, fixUrl(itemUrl), TvType.Anime) {
                        this.posterUrl = fixUrlNull(item.jetpack_featured_media_url)
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1 span:not([class*='jp']), h1")?.text()?.trim()
