/*
 * Copyright (C) 2026  Karelisio
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Prints the raw answers of the lyrics services to build/provider-diagnostics.txt, to see why a
 * provider finds nothing. Skipped unless RUSHIO_LIVE_TESTS=true.
 */
class ProviderDiagnosticsTest {
    private val report = StringBuilder()
    private val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        install(HttpCookies)
        expectSuccess = false
    }

    private suspend fun probe(label: String, call: suspend () -> HttpResponse): String {
        val text =
            try {
                val response = call()
                val body = response.bodyAsText()
                report.appendLine("## $label -> ${response.status.value}")
                report.appendLine(body.replace("\n", " ").replace(Regex("\\s+"), " ").take(600))
                body
            } catch (e: Exception) {
                report.appendLine("## $label -> ${e::class.simpleName}: ${e.message}")
                ""
            }
        report.appendLine()
        return text
    }

    private fun HttpRequestBuilder.qq(chinaIp: Boolean = false) {
        header("User-Agent", UA)
        header("Referer", "https://y.qq.com/")
        if (chinaIp) {
            header("X-Real-IP", CHINA_IP)
            header("X-Forwarded-For", CHINA_IP)
        }
    }

    private suspend fun musicu(label: String, body: String, chinaIp: Boolean = false) =
        probe(label) {
            client.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                qq(chinaIp)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    @Test
    fun dumpRawAnswers(): Unit = runBlocking {
        if (System.getenv("RUSHIO_LIVE_TESTS") != "true") return@runBlocking
        val query = "晴天 周杰伦"
        val desktopSearch =
            """{"comm":{"ct":"19","cv":"1859","uin":"0"},"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService","param":{"grp":1,"num_per_page":3,"page_num":1,"query":"$query","search_type":0}}}"""

        musicu("qq musicu desktop search + China IP", desktopSearch, chinaIp = true)
        musicu(
            "qq musicu mobile search",
            """{"comm":{"ct":11,"cv":"1003006","v":"1003006","tmeAppID":"qqmusic","uin":"0","format":"json"},"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicMobile","param":{"query":"$query","search_type":0,"num_per_page":3,"page_num":1,"highlight":0,"grp":1}}}""",
        )
        musicu(
            "qq musicu lite search",
            """{"comm":{"ct":"1","cv":"0","uin":"0"},"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicLite","param":{"query":"$query","search_type":0,"num_per_page":3,"page_num":1}}}""",
        )
        probe("qq smartbox") {
            client.get("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg") {
                qq()
                parameter("key", query)
                parameter("format", "json")
                parameter("inCharset", "utf-8")
                parameter("outCharset", "utf-8")
            }
        }
        probe("qq client_search_cp + China IP") {
            client.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp") {
                qq(chinaIp = true)
                parameter("w", query)
                parameter("format", "json")
                parameter("p", 1)
                parameter("n", 3)
                parameter("new_json", 1)
            }
        }

        // Lyrics of 晴天 (songmid 0039MnYb0qxYhV, songid 97773), independently of the search
        probe("qq fcg_query_lyric_new") {
            client.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
                qq()
                parameter("songmid", "0039MnYb0qxYhV")
                parameter("format", "json")
                parameter("nobase64", 1)
                parameter("g_tk", 5381)
            }
        }
        musicu(
            "qq musicu GetPlayLyricInfo",
            """{"comm":{"ct":"19","cv":"1859","uin":"0"},"req_1":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"0039MnYb0qxYhV","songID":97773}}}""",
        )

        // Musixmatch: tokens of the desktop and iOS apps
        for ((label, url) in
            listOf(
                "desktop" to
                    "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0",
                "ios" to "https://apic.musixmatch.com/ws/1.1/token.get?app_id=mac-ios-v2.0",
            )) {
            val body =
                probe("musixmatch $label token.get") {
                    client.get(url) {
                        header("Cookie", "AWSELBCORS=0; AWSELB=0")
                        parameter("user_language", "en")
                        parameter("t", System.currentTimeMillis())
                    }
                }
            val token = Regex("\"user_token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            report.appendLine("musixmatch $label token: ${token?.take(8)}…\n")
        }

        println(report)
        File("build/provider-diagnostics.txt")
            .apply { parentFile.mkdirs() }
            .writeText(report.toString())
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        const val CHINA_IP = "116.25.146.177"
    }
}
