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
import com.shub39.rush.shared.logic.network.lyrics.NeteaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
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
                report.appendLine(body.replace("\n", " ").take(700))
                body
            } catch (e: Exception) {
                report.appendLine("## $label -> ${e::class.simpleName}: ${e.message}")
                ""
            }
        report.appendLine()
        return text
    }

    private fun HttpRequestBuilder.desktop(referer: String) {
        header("User-Agent", UA)
        header("Referer", referer)
    }

    @Test
    fun dumpRawAnswers(): Unit = runBlocking {
        if (System.getenv("RUSHIO_LIVE_TESTS") != "true") return@runBlocking
        val query = "Bohemian Rhapsody Queen"

        // NetEase: web search, same with a mainland China IP, eapi cloud search
        probe("netease web search") {
            client.get("https://music.163.com/api/search/get/web") {
                desktop("https://music.163.com/")
                parameter("s", query)
                parameter("type", 1)
                parameter("limit", 3)
            }
        }
        val neteaseAnswers = mutableListOf<String>()
        neteaseAnswers +=
            probe("netease web search + X-Real-IP") {
                client.get("https://music.163.com/api/search/get/web") {
                    desktop("https://music.163.com/")
                    header("X-Real-IP", CHINA_IP)
                    header("X-Forwarded-For", CHINA_IP)
                    parameter("s", query)
                    parameter("type", 1)
                    parameter("limit", 3)
                }
            }
        neteaseAnswers +=
            probe("netease cloudsearch pc + X-Real-IP") {
                client.submitForm(
                    url = "https://music.163.com/api/cloudsearch/pc",
                    formParameters =
                        parameters {
                            append("s", query)
                            append("type", "1")
                            append("limit", "3")
                            append("offset", "0")
                        },
                ) {
                    desktop("https://music.163.com/")
                    header("X-Real-IP", CHINA_IP)
                    header("Cookie", "appver=2.9.11; os=pc")
                }
            }
        neteaseAnswers +=
            probe("netease eapi cloudsearch + X-Real-IP") {
                val payload =
                    """{"s":"$query","type":1,"limit":3,"offset":0,"total":true,"header":{"os":"pc","appver":"2.9.11"}}"""
                client.submitForm(
                    url = "https://interface3.music.163.com/eapi/cloudsearch/pc",
                    formParameters =
                        parameters {
                            append(
                                "params",
                                NeteaseProvider.eapiParams("/api/cloudsearch/pc", payload),
                            )
                        },
                ) {
                    desktop("https://music.163.com/")
                    header("X-Real-IP", CHINA_IP)
                    header("Cookie", "appver=2.9.11; os=pc")
                }
            }
        val neteaseId =
            neteaseAnswers.firstNotNullOfOrNull {
                Regex("\"songs\":\\[\\{\"(?:name\":\"[^\"]*\",\")?id\":(\\d+)")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
            }
        report.appendLine("netease first song id: $neteaseId\n")
        if (neteaseId != null) {
            probe("netease classic lyric") {
                client.get("https://music.163.com/api/song/lyric") {
                    desktop("https://music.163.com/")
                    header("X-Real-IP", CHINA_IP)
                    parameter("id", neteaseId)
                    parameter("lv", -1)
                    parameter("tv", -1)
                }
            }
        }

        // QQ Music: musicu search, then lyrics of the first song
        val qqSearch =
            probe("qq musicu search") {
                client.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    desktop("https://y.qq.com/")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"comm":{"ct":"19","cv":"1859","uin":"0"},"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService","param":{"grp":1,"num_per_page":3,"page_num":1,"query":"$query","search_type":0}}}"""
                    )
                }
            }
        val mid = Regex("\"mid\":\"([0-9A-Za-z]{14})\"").find(qqSearch)?.groupValues?.get(1)
        val songId = Regex("\"id\":(\\d{5,})").find(qqSearch)?.groupValues?.get(1)
        report.appendLine("qq first song: mid=$mid id=$songId\n")
        if (mid != null) {
            probe("qq fcg_query_lyric_new") {
                client.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
                    desktop("https://y.qq.com/")
                    parameter("songmid", mid)
                    parameter("format", "json")
                    parameter("nobase64", 1)
                    parameter("g_tk", 5381)
                }
            }
            probe("qq musicu GetPlayLyricInfo") {
                client.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    desktop("https://y.qq.com/")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"comm":{"ct":"19","cv":"1859","uin":"0"},"req_1":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$mid","songID":${songId ?: 0}}}}"""
                    )
                }
            }
        }

        // Musixmatch: token, search, subtitles, macro call
        val tokenBody =
            probe("musixmatch token.get") {
                client.get("$MXM/token.get") {
                    header("Cookie", "x-mxm-token-guid=")
                    parameter("app_id", MXM_APP)
                    parameter("user_language", "en")
                    parameter("t", System.currentTimeMillis())
                }
            }
        val token = Regex("\"user_token\":\"([^\"]+)\"").find(tokenBody)?.groupValues?.get(1)
        if (token != null) {
            val search =
                probe("musixmatch track.search") {
                    client.get("$MXM/track.search") {
                        parameter("app_id", MXM_APP)
                        parameter("usertoken", token)
                        parameter("q_track", "Bohemian Rhapsody")
                        parameter("q_artist", "Queen")
                        parameter("page_size", 2)
                        parameter("s_track_rating", "desc")
                    }
                }
            val trackId = Regex("\"track_id\":(\\d+)").find(search)?.groupValues?.get(1)
            if (trackId != null) {
                probe("musixmatch track.subtitle.get") {
                    client.get("$MXM/track.subtitle.get") {
                        parameter("app_id", MXM_APP)
                        parameter("usertoken", token)
                        parameter("track_id", trackId)
                        parameter("subtitle_format", "lrc")
                    }
                }
            }
            probe("musixmatch macro.subtitles.get") {
                client.get("$MXM/macro.subtitles.get") {
                    parameter("app_id", MXM_APP)
                    parameter("usertoken", token)
                    parameter("format", "json")
                    parameter("namespace", "lyrics_richsynched")
                    parameter("subtitle_format", "lrc")
                    parameter("q_track", "Bohemian Rhapsody")
                    parameter("q_artist", "Queen")
                }
            }
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
        const val MXM = "https://apic-desktop.musixmatch.com/ws/1.1"
        const val MXM_APP = "web-desktop-app-v1.0"
    }
}
