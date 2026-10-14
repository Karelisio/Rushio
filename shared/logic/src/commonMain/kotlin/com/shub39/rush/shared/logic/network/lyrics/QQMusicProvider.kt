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
package com.shub39.rush.shared.logic.network.lyrics

import com.shub39.rush.shared.core.RushLogger
import com.shub39.rush.shared.core.dataclasses.LyricsCandidate
import com.shub39.rush.shared.core.dataclasses.TrackQuery
import com.shub39.rush.shared.core.enums.LyricsProviderId
import com.shub39.rush.shared.core.getMainArtist
import com.shub39.rush.shared.core.getMainTitle
import com.shub39.rush.shared.core.util.LrcUtils
import com.shub39.rush.shared.core.util.TrackMatcher
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.core.annotation.Single

/** QQ Music, line synced lyrics only. */
@Single
class QQMusicProvider : LyricsProvider {
    override val id = LyricsProviderId.QQ_MUSIC

    private data class Song(
        val mid: String,
        val id: Long?,
        val name: String,
        val artists: String,
        val album: String?,
        val durationMs: Long?,
    )

    private val client by lazy {
        lyricsHttpClient {
            defaultRequest {
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                header(HttpHeaders.Referrer, "https://y.qq.com/")
            }
        }
    }

    override suspend fun search(query: TrackQuery, limit: Int): List<LyricsCandidate> {
        val songs = searchSongs(query)
        return TrackMatcher.ranked(query, songs, { it.name }, { it.artists }, { it.durationMs })
            .take(limit)
            .mapNotNull { fetchLyrics(it) }
    }

    private suspend fun searchSongs(query: TrackQuery): List<Song> {
        val data =
            musicu(module = "music.search.SearchCgiService", method = "DoSearchForQQMusicDesktop") {
                put("query", "${getMainTitle(query.title)} ${getMainArtist(query.artist)}")
                put("search_type", 0)
                put("num_per_page", 10)
                put("page_num", 1)
                put("grp", 1)
            }

        val songs = data.obj("body").obj("song").arr("list") ?: return emptyList()
        return songs.mapNotNull { song ->
            Song(
                mid = song.str("mid") ?: song.str("songmid") ?: return@mapNotNull null,
                id = song.long("id") ?: song.long("songid"),
                name =
                    song.str("name")
                        ?: song.str("title")
                        ?: song.str("songname")
                        ?: return@mapNotNull null,
                artists =
                    song.arr("singer")?.mapNotNull { it.str("name") }?.joinToString(", ") ?: "",
                album = song.obj("album").str("name") ?: song.str("albumname"),
                durationMs = song.long("interval")?.times(1000),
            )
        }
    }

    /**
     * Calls the "musicu" gateway used by the QQ Music clients and returns the `data` of the
     * request.
     */
    private suspend fun musicu(
        module: String,
        method: String,
        params: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val request = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            }
            putJsonObject("req_1") {
                put("module", module)
                put("method", method)
                putJsonObject("param", params)
            }
        }

        val body =
            client
                .post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    contentType(ContentType.Application.Json)
                    setBody(request.toString())
                }
                .bodyAsText()

        val response = parseJson(body).obj("req_1")
        require(response.long("code") == 0L) { "QQ Music $method code ${response.long("code")}" }
        return response.obj("data") ?: JsonObject(emptyMap())
    }

    private suspend fun fetchLyrics(song: Song): LyricsCandidate? {
        val lyric =
            try {
                musicu(module = "music.musichallSong.PlayLyricInfo", method = "GetPlayLyricInfo") {
                        put("songMID", song.mid)
                        song.id?.let { put("songID", it) }
                    }
                    .str("lyric")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RushLogger.e(TAG, "musicu lyrics failed, falling back to the classic api", e)
                classicLyrics(song.mid)
            }

        val lrc = lyric?.let(::decodeLyric)?.let(LrcUtils::normalize) ?: return null

        return LyricsCandidate(
            provider = id,
            title = song.name,
            artist = song.artists,
            album = song.album,
            durationMs = song.durationMs,
            plain = LrcUtils.toPlain(lrc),
            lrc = lrc,
        )
    }

    private suspend fun classicLyrics(mid: String): String? {
        val body =
            client
                .get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
                    parameter("songmid", mid)
                    parameter("format", "json")
                    parameter("nobase64", 1)
                    parameter("g_tk", 5381)
                    parameter("loginUin", 0)
                    parameter("hostUin", 0)
                    parameter("inCharset", "utf8")
                    parameter("outCharset", "utf-8")
                    parameter("notice", 0)
                    parameter("platform", "yqq.json")
                    parameter("needNewCode", 0)
                }
                .bodyAsText()
        return parseJson(body).str("lyric")
    }

    companion object {
        private const val TAG = "QQMusicProvider"
        private val numericEntity = Regex("""&#(\d+);""")

        /** QQ answers JSONP ("MusicJsonCallback({...})") unless format=json is honoured. */
        fun parseJson(body: String): JsonElement {
            val trimmed = body.trim()
            val json =
                if (trimmed.startsWith("{")) trimmed
                else trimmed.substringAfter("(").substringBeforeLast(")")
            return Json.parseToJsonElement(json)
        }

        /** Lyrics are base64 encoded, or html escaped when sent with nobase64=1. */
        fun decodeLyric(value: String): String {
            if (value.isBlank()) return value
            if (value.contains('[') || value.contains("&#")) {
                return numericEntity
                    .replace(value) { it.groupValues[1].toInt().toChar().toString() }
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
            }
            return runCatching { Base64.Default.decode(value.trim()).decodeToString() }
                .getOrDefault(value)
        }
    }
}
