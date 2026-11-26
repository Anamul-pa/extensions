package com.desidubanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest & Trending",
        "$mainUrl/search/?s_orderby=updated&s_order=desc&s_paged=" to "Latest Updates",
        "$mainUrl/search/?s_orderby=date&s_order=desc&s_type[]=movie&s_paged=" to "Latest Movies",
        "$mainUrl/search/?s_orderby=popular&s_paged=" to "Most Popular"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "$mainUrl/") {
            if (page == 1) "$mainUrl/" else "$mainUrl/search/?s_orderby=updated&s_order=desc&s_paged=$page"
        } else {
            "${request.data}$page"
        }

        val response = app.get(url, headers = headers)
        val document = response.document
        val cards = document.select("article.anime-card")

        val home = if (cards.isNotEmpty()) {
            cards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        } else {
            // যদি কোনো কার্ড না পায়, তবে কেন পাচ্ছে না তা স্ক্রিনে দেখাবে
            val pageTitle = document.title().ifEmpty { "No Title" }
            listOf(
                newAnimeSearchResponse(
                    "DEBUG [${response.code}]: $pageTitle (Cards: 0)",
                    mainUrl,
                    TvType.Anime
                )
            )
        }

        return newHomePageResponse(request.name, home)
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
        val response = app.get("$mainUrl/search/?s_keyword=$query", headers = headers)
        val cards = response.document.select("article.anime-card")
        if (cards.isEmpty()) {
            return listOf(
                newAnimeSearchResponse(
                    "SEARCH DEBUG [${response.code}]: ${response.document.title()}",
                    mainUrl,
                    TvType.Anime
                )
            )
        }
        return cards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1 span:not([class*='jp']), h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.aspect-\\[3\\/4\\] img, img.wp-post-image, main img")?.attr("src")
        )
        val description = document.selectFirst("div.text-muted p, div.entry-content, p.line-clamp-3")?.text()?.trim()

        val episodes = document.select("a[href*='/watch/']").mapNotNull { a ->
            val epHref = fixUrl(a.attr("href"))
            val text = a.text().trim()
            if (text.equals("Watch Now", ignoreCase = true)) return@mapNotNull null

            val epNum = Regex("""(?:Episode|Ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = if (text.isNotBlank()) text else "Episode ${epNum ?: 1}"
                this.episode = epNum
            }
        }.distinctBy { it.data }

        val finalEpisodes = if (episodes.isEmpty() && url.contains("/watch/")) {
            listOf(newEpisode(url) {
                this.name = "Watch"
                this.episode = 1
            })
        } else {
            episodes
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Dubbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("googletagmanager") && !src.contains("facebook")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        document.select("[data-embed], [data-src], [data-url]").forEach { el ->
            val raw = el.attr("data-embed").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-url") }
            if (raw.isNotBlank()) {
                val decoded = try {
                    String(Base64.decode(raw, Base64.DEFAULT))
                } catch (e: Exception) {
                    raw
                }
                val iframeSrc = Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1) ?: decoded
                if (iframeSrc.startsWith("http")) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
