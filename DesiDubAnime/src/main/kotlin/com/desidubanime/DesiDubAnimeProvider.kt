package com.desidubanime

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

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "Latest Anime",
        "$mainUrl/movies/page/" to "Anime Movies",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.bs, div.listupd article, article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.tt, h2, h3, .title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs, div.listupd article, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb img, div.poster img")?.attr("src"))
        val description = document.selectFirst("div.entry-content, div.sinopsis, p")?.text()?.trim()

        val episodes = document.select("div.eplister ul li, ul.episodios li").mapNotNull { ep ->
            val a = ep.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epName = ep.selectFirst("div.epl-title, .episodiotitle")?.text()?.trim() ?: a.text().trim()
            val epNum = ep.selectFirst("div.epl-num, .numerando")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // পেজে থাকা সব iframe ভিডিও সোর্স খুঁজে বের করা
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        // ড্রপডাউন বা সার্ভার বাটনে লুকানো base64/iframe লিংক চেক করা
        document.select("select.mirror option, div.player-option").forEach { option ->
            val encoded = option.attr("value").ifEmpty { option.attr("data-embed") }
            if (encoded.isNotBlank()) {
                val decoded = try {
                    base64Decode(encoded)
                } catch (e: Exception) {
                    encoded
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
