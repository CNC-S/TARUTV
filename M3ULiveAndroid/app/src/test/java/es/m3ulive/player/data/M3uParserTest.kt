package es.m3ulive.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun parsesLiveChannelsAndMetadata() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.es" tvg-name="Noticias" tvg-logo="https://img.example/news.png" group-title="General",Canal Noticias
            https://stream.example/news/index.m3u8
            #EXTINF:-1 group-title="Deportes",Canal Deportivo
            https://stream.example/sport/live.ts
        """.trimIndent()

        val channels = M3uParser.parse(playlist)

        assertEquals(2, channels.size)
        assertEquals("Canal Noticias", channels[0].name)
        assertEquals("General", channels[0].group)
        assertEquals("news.es", channels[0].tvgId)
        assertEquals("https://img.example/news.png", channels[0].logoUrl)
        assertEquals("https://stream.example/news/index.m3u8", channels[0].streamUrl)
        assertEquals("Canal Deportivo", channels[1].name)
        assertEquals("Deportes", channels[1].group)
    }

    @Test
    fun readsCommonInlineHttpHeaders() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="General",Canal protegido
            https://stream.example/live.ts|User-Agent=MyPlayer%2F1.0&Referer=https%3A%2F%2Fweb.example
        """.trimIndent()

        val channel = M3uParser.parse(playlist).single()

        assertEquals("MyPlayer/1.0", channel.headers["User-Agent"])
        assertEquals("https://web.example", channel.headers["Referer"])
        assertTrue(channel.id.isNotBlank())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContentWithoutExtm3uHeader() {
        M3uParser.parse("https://stream.example/live.ts")
    }
}
