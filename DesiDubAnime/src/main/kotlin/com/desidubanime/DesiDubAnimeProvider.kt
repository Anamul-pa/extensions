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
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,একদম আসল কারণটি ধরা পড়ে গেছে! ছবির কার্ডে লেখা উঠেছে **`DEBUG [403]: Just a moment...`**—অর্থাৎ **Cloudflare Security** ওয়েবসাইটটিকে অ্যাপের সাধারণ রিকোয়েস্ট থেকে ব্লক করে রেখেছে।

আগে যখন আমরা `CloudflareKiller` ব্যবহার করেছিলাম, তখন আপনার পুরনো রিপোজিটরি ভার্সনে ওটির ক্লাস পাথ ভিন্ন ছিল বলে এরর এসেছিল। এখন আমরা এটিকে দুইভাবে বাইপাস করব:
1. আপনার রিপোজিটরির **`build.gradle.kts`** আপডেট করে `CloudflareKiller`-এর সঠিক লাইব্রেরি পাথ সচল করা, **অথবা** সরাসরি CloudStream-এর বিল্ট-ইন **WebView / Cloudflare Interceptor** ব্যবহার করা।
2. যেহেতু ওয়েবসাইটটি **WordPress** দিয়ে তৈরি, তাই এর **WordPress REST API (`/wp-json/wp/v2/...`)** অথবা ওয়েবভিউ কুকি দিয়ে কোনো Cloudflare ছাড়াই ডাটা আনা সম্ভব কি না তাও সাথে যুক্ত করে দিচ্ছি।

নিচের দুটি সহজ ধাপ অনুসরণ করলে `403 Just a moment...` ব্লকটি ছুটে যাবে এবং অ্যানিমেগুলো চলে আসবে:

---

### ধাপ ১: `DesiDubAnime/build.gradle.kts` আপডেট করা (যাতে সর্বশেষ CloudStream লাইব্রেরি পায়)

1. নিচের লিংকে ক্লিক করে `DesiDubAnime/build.gradle.kts` এডিট পেজে যান:
   `https://github.com/Anamul-pa/extensions/edit/master/DesiDubAnime/build.gradle.kts`

2. ভেতরের সব লেখা মুছে নিচের কোডটি পেস্ট করে **Commit changes** দিন (এখানে ভার্সন `4` করে দেওয়া হয়েছে):

```kotlin
version = 4

cloudstream {
    description = "Hindi Dubbed Anime from DesiDubAnime"
    language = "hi"
    authors = listOf("Anamul")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "[https://www.google.com/s2/favicons?domain=desidubanime.me&sz=%size](https://www.google.com/s2/favicons?domain=desidubanime.me&sz=%size)%"
}
