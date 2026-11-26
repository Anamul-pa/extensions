package com.desidubanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    private val cfInterceptor = CloudflareKiller()
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest & Trending",
        "$mainUrl/search/?s_orderby=updated&s_order=desc&s_paged=" to "Latest Updates",
        "$mainUrl/search/?s_orderby=date&s_order=desc&s_type[]=movie&s_paged=" to "Latest Movies",
        "$mainUrl/search/?s_orderby=popular&s_paged=" to "Most Popular",
        "$mainUrl/tag/hindi/page/" to "Hindi Dubbed"
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

        val document = app.get(url, headers = headers, interceptor = cfInterceptor).document
        val home = document.select("article.anime-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

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
        val document = app.get(
            "$mainUrl/search/?s_keyword=$query",
            headers = headers,
            interceptor = cfInterceptor
        ).document

        return document.select("article.anime-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers, interceptor = cfInterceptor).document

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
        val document = app.get(data, headers = headers, interceptor = cfInterceptor).document

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
