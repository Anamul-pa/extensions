package com.desidubanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun getCurrentActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                if (activityRecord == null) continue
                val recordClass = activityRecord.javaClass
                val pausedField = recordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = recordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as? Activity
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getAppContext() = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        (activityThread.getMethod("currentApplication").invoke(null) as? Application)?.applicationContext
    } catch (_: Exception) {
        null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadHtmlViaWebView(targetUrl: String): String {
        val activity = getCurrentActivity()
        val ctx = activity ?: getAppContext() ?: return ""
        val latch = CountDownLatch(1)
        var resultHtml = ""

        Handler(Looper.getMainLooper()).post {
            var dialog: AlertDialog? = null
            var webView: WebView? = null
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                webView = WebView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        900
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                }

                cookieManager.setAcceptThirdPartyCookies(webView, true)

                if (activity != null && !activity.isFinishing) {
                    val container = FrameLayout(activity)
                    container.addView(webView)
                    dialog = AlertDialog.Builder(activity)
                        .setTitle("Verifying DesiDubAnime...")
                        .setView(container)
                        .setCancelable(true)
                        .setOnCancelListener {
                            latch.countDown()
                        }
                        .create()
                    dialog?.show()
                }

                val handler = Handler(Looper.getMainLooper())
                var checks = 0

                val checker = object : Runnable {
                    override fun run() {
                        checks++
                        webView?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            val html = try {
                                if (raw != null && raw != "null") {
                                    JSONTokener(raw).nextValue().toString()
                                } else ""
                            } catch (_: Exception) {
                                ""
                            }

                            if (html.contains("anime-card") || html.contains("kiranime") || html.contains("entry-content") || html.contains("<iframe")) {
                                resultHtml = html
                                try { dialog?.dismiss() } catch (_: Exception) {}
                                try { webView?.destroy() } catch (_: Exception) {}
                                latch.countDown()
                            } else if (checks < 25) {
                                handler.postDelayed(this, 1000)
                            } else {
                                resultHtml = html
                                try { dialog?.dismiss() } catch (_: Exception) {}
                                try { webView?.destroy() } catch (_: Exception) {}
                                latch.countDown()
                            }
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        handler.postDelayed(checker, 1500)
                    }
                }

                webView.loadUrl(targetUrl)
            } catch (_: Exception) {
                try { dialog?.dismiss() } catch (_: Exception) {}
                latch.countDown()
            }
        }

        try {
            latch.await(28, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return resultHtml
    }

    private fun fetchDocument(url: String): Document {
        val html = loadHtmlViaWebView(url)
        return Jsoup.parse(html, mainUrl)
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
                    "WEBVIEW: ${document.title().ifEmpty { "Timeout/Empty" }}",
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
