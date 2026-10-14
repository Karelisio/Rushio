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
import com.shub39.rush.shared.core.Result
import com.shub39.rush.shared.core.dataclasses.TrackQuery
import com.shub39.rush.shared.logic.network.LrcLibApi
import com.shub39.rush.shared.logic.network.lyrics.LrcLibProvider
import com.shub39.rush.shared.logic.network.lyrics.MusixmatchProvider
import com.shub39.rush.shared.logic.network.lyrics.NeteaseProvider
import com.shub39.rush.shared.logic.network.lyrics.QQMusicProvider
import com.shub39.rush.shared.logic.network.lyrics.safeSearch
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * Queries the real lyrics services and writes a report to build/live-providers.txt. Skipped unless
 * RUSHIO_LIVE_TESTS=true (workflow "Check lyrics providers"): it needs internet access and the
 * answers of these unofficial services change over time.
 */
class LiveProvidersTest {
    private val tracks =
        listOf(
            TrackQuery(title = "Si seulement", artist = "2TH"),
            TrackQuery(title = "Bohemian Rhapsody", artist = "Queen", durationMs = 354_000),
            TrackQuery(title = "Blinding Lights", artist = "The Weeknd", durationMs = 200_000),
            TrackQuery(title = "Dernière danse", artist = "Indila", durationMs = 214_000),
            TrackQuery(title = "晴天", artist = "周杰伦", durationMs = 269_000),
        )

    @Test
    fun everyProviderFindsLyrics(): Unit = runBlocking {
        if (System.getenv("RUSHIO_LIVE_TESTS") != "true") return@runBlocking

        val providers =
            listOf(
                LrcLibProvider(LrcLibApi()),
                NeteaseProvider(),
                QQMusicProvider(),
                MusixmatchProvider(),
            )
        val report = StringBuilder()
        val broken = mutableListOf<String>()

        for (provider in providers) {
            var found = 0
            var blocked = false
            for (track in tracks) {
                val line =
                    when (
                        val result = provider.safeSearch(track, limit = 3, timeoutMillis = 30_000)
                    ) {
                        is Result.Success -> {
                            found++
                            val best = result.data.first()
                            val lines = (best.lrc ?: best.plain).orEmpty().lines().size
                            "OK ${best.quality} « ${best.title} » ${best.artist}, $lines lignes"
                        }
                        is Result.Error -> {
                            // Musixmatch blocks datacenter networks like the CI runners
                            if (result.message.orEmpty().contains("blocked")) blocked = true
                            "ÉCHEC ${result.error} ${result.message.orEmpty()}"
                        }
                    }
                report.appendLine("${provider.id} | ${track.title} — ${track.artist} | $line")
            }
            if (found == 0 && !blocked) broken += provider.id.name
        }

        println(report)
        File("build/live-providers.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
        if (broken.isNotEmpty()) fail("No lyrics at all from: $broken\n$report")
    }
}
