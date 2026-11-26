package com.desidubanime

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

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

    private var savedCookies: String = ""
    private var savedUserAgent: String =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun getAppContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApp = activityThread.getMethod("currentApplication").invoke(null) as? Application
            currentApp?.applicationContext
        } catch (_: Exception) {
            null
        }
    }

    private fun getHeaders(): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to savedUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$mainUrl/"
        )
        if (savedCookies.isNotBlank()) {
            map["Cookie"] = savedCookies
        }
        return map
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bypassCloudflareWithWebView(targetUrl: String) {
        val ctx = getAppContext() ?: return
        val done = AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val webView = WebView(ctx)
                val settings: WebSettings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                savedUserAgent = settings.userAgentString ?: savedUserAgent

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val cookies = cookieManager.getCookie(mainUrl) ?: ""
                        val pageTitle = view?.title ?: ""
                        if (cookies.contains("cf_clearance") || (!pageTitle.contains("Just a moment") && pageTitle.isNotBlank())) {
                            savedCookies = cookies
                            done.set(true)
                        }
                    }
                }
                webView.loadUrl(targetUrl)
            } catch (_: Exception) {
                done.set(true)
            }
        }

        var waited = 0
        while (!done.get() && waited < 15000) {
            try {
                Thread.sleep(500)
            } catch (_: Exception) {
                break
            }
            waited += 500
            val currentCookies = try {
                CookieManager.getInstance().getCookie(mainUrl) ?: ""
            } catch (_: Exception) {
                ""
            }
            if (currentCookies.contains("cf_clearance")) {
                savedCookies = currentCookies
                break
            }
        }
    }

    private suspend fun fetchDocument(url: String): Document {
        var res = app.get(url, headers = getHeaders())
        if (res.code == 403 || res.text.contains("Just a moment...")) {
            bypassCloudflareWithWebView(url)
            res = app.get(url, headers = getHeaders())
        }
        return Jsoup.parse(res.text, mainUrl)
    }

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

        val document = fetchDocument(url)
        val cards = document.select("article.anime-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        val home = if (cards.isNotEmpty()) {
            cards
        } else {
            listOf(
                newAnimeSearchResponse(
                    "CF STATUS: ${document.title().ifEmpty { "No Cards" }}",
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
        val document = fetchDocument("$mainUrl/search/?s_keyword=$query")
        return document.select("article.anime-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url)
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
        val document = fetchDocument(data)

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
