package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the lyrics parsers against the shapes LRCLIB really returns (samples trimmed from real records). */
class LyricsParseTest {
    private val lyricsfile = """
version: '1.0'
metadata:
  title: Blinding Lights
  artist: Teddy Swims
  duration_ms: 215000
  instrumental: false
lines:
- text: I been tryna call
  start_ms: 29470
  end_ms: 32330
  words:
  - text: 'I '
    start_ms: 29470
  - text: 'been '
    start_ms: 29843
  - text: 'tryna '
    start_ms: 30020
  - text: call
    start_ms: 30414
- text: I'm going through withdrawals
  start_ms: 41130
  end_ms: 44400
  words:
  - text: 'I''m '
    start_ms: 41130
  - text: withdrawals
    start_ms: 42147
""".trimIndent()

    @Test fun wordSynced() {
        val s = Lyrics.parseLyricsfile(lyricsfile, 215_000)
        assertEquals(Lyrics.Kind.WORDS, s.kind)
        // intro break (29 s before the first line) + 2 lines + a break between them (8.8 s gap)
        val text = s.lines.filter { it.text.isNotEmpty() }
        assertEquals(2, text.size)
        assertEquals("I been tryna call", text[0].text)
        assertEquals(4, text[0].words.size)
        assertEquals("I ", text[0].words[0].text)
        assertEquals(29843L, text[0].words[0].endMs)
        assertEquals(32330L, text[0].words[3].endMs)
        assertEquals("I'm ", text[1].words[0].text)
        assertTrue(s.lines.first().text.isEmpty())
        assertEquals(1, s.indexAt(29_500))
    }

    @Test fun lineSynced() {
        val lrc = "[00:17.12] I feel your breath upon my neck\n[00:20.41] A soft caress as cold as death\n[00:29.60] I didn't know you"
        val s = Lyrics.parseLrc(lrc, 233_000)
        assertEquals(Lyrics.Kind.LINES, s.kind)
        val text = s.lines.filter { it.text.isNotEmpty() }
        assertEquals(3, text.size)
        assertEquals(17_120L, text[0].startMs)
        assertEquals(20_410L, text[0].endMs)
    }

    @Test fun enhancedLrcWords() {
        val s = Lyrics.parseLrc("[00:01.00]<00:01.00>Hello <00:01.50>world", 60_000)
        assertEquals(Lyrics.Kind.WORDS, s.kind)
        assertEquals("Hello world", s.lines.first().text)
        assertEquals(2, s.lines.first().words.size)
    }

    @Test fun plainEstimated() {
        val s = Lyrics.estimatePlain("Line one\nLine two\n\nLine three", 200_000)!!
        assertEquals(Lyrics.Kind.PLAIN, s.kind)
        assertTrue(s.lines.first().startMs > 0)
        assertTrue(s.lines.last().endMs <= 200_000)
        assertTrue(s.lines.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test fun titleCleanup() {
        assertEquals("Song", Lyrics.cleanTitle("Song - Remastered 2011"))
        assertEquals("Song", Lyrics.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song - Part 2", Lyrics.cleanTitle("Song - Part 2"))
    }
}

/** The lyrics cache keeps the 500 most recently played songs. */
class LyricsCacheTest {
    @Test fun keepsMostRecent500() {
        val dir = kotlin.io.path.createTempDirectory("lyrics").toFile()
        try {
            val now = System.currentTimeMillis()
            // 530 songs; song i was last played i minutes after the first.
            for (i in 0 until 530) {
                java.io.File(dir, "song$i.json").apply { writeText("{}"); setLastModified(now - 1_000_000_000L + i * 60_000L) }
            }
            Lyrics.trim(dir)
            val left = dir.listFiles()!!.map { it.name }.toSet()
            assertEquals(Lyrics.KEEP_SONGS, left.size)
            assertTrue("newest kept", "song529.json" in left && "song30.json" in left)
            assertTrue("oldest removed", "song0.json" !in left && "song29.json" !in left)
        } finally { dir.deleteRecursively() }
    }

    @Test fun leavesSmallCacheAlone() {
        val dir = kotlin.io.path.createTempDirectory("lyrics").toFile()
        try {
            for (i in 0 until 510) java.io.File(dir, "s$i.json").writeText("{}")
            Lyrics.trim(dir)
            assertEquals(510, dir.listFiles()!!.size) // under the trim threshold: nothing happens
        } finally { dir.deleteRecursively() }
    }
}
