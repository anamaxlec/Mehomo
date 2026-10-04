package dev.memoh.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class AppRelease(val version: String, val name: String, val notes: String, val pageUrl: String,
    val apkUrl: String?, val apkSize: Long?)

/** Numeric comparison, including the ordering of SemVer prerelease identifiers. */
fun compareReleaseVersions(left: String, right: String): Int? {
    fun parse(value: String): Pair<List<Long>, List<String>>? {
        val match = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value.trim()) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        return numbers to match.groupValues[2].takeIf(String::isNotBlank)?.split('.').orEmpty()
    }
    val (a, ap) = parse(left) ?: return null
    val (b, bp) = parse(right) ?: return null
    repeat(maxOf(a.size, b.size)) { i -> (a.getOrElse(i) { 0L }).compareTo(b.getOrElse(i) { 0L }).takeIf { it != 0 }?.let { return it } }
    if (ap.isEmpty() || bp.isEmpty()) return when { ap.isEmpty() && bp.isEmpty() -> 0; ap.isEmpty() -> 1; else -> -1 }
    repeat(minOf(ap.size, bp.size)) { i ->
        val an = ap[i].toLongOrNull(); val bn = bp[i].toLongOrNull()
        val result = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> ap[i].compareTo(bp[i]) }
        if (result != 0) return result
    }
    return ap.size.compareTo(bp.size)
}

class GitHubReleases(private val client: OkHttpClient, private val json: Json,
    private val endpoint: String = "https://api.github.com/repos/anamaxlec/Mehomo/releases/latest") {
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Mehomo-Android").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException(when (response.code) {
                403, 429 -> "GitHub 请求频率受限，请稍后重试"
                else -> "无法读取 GitHub 发行版（${response.code}）"
            })
            val release = json.parseToJsonElement(response.body?.string() ?: throw IOException("GitHub 返回了空内容")).jsonObject
            fun string(key: String) = (release[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if ((release["draft"] as? JsonPrimitive)?.booleanOrNull == true || (release["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return@withContext null
            val tag = string("tag_name")
            if (compareReleaseVersions(tag, tag) == null) throw IOException("发行版版本号无法识别")
            val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val apk = assets.firstOrNull { asset ->
                val name = (asset["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                name.endsWith("-release.apk", true) && !name.contains("debug", true) && (asset["state"] as? JsonPrimitive)?.contentOrNull == "uploaded"
            }
            AppRelease(tag.removePrefix("v").removePrefix("V"), string("name").ifBlank { tag }, string("body"), string("html_url"),
                (apk?.get("browser_download_url") as? JsonPrimitive)?.contentOrNull,
                (apk?.get("size") as? JsonPrimitive)?.longOrNull)
        }
    }
}
