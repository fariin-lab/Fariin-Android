package com.fariin.app.data

import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.TextStoryRules.LinkStyle as S
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewsTest {
    private fun page(name: String): String =
        javaClass.classLoader!!.getResource("linkpreview/$name")!!.readText()

    @Test fun savedBbcPageReadsTitleAndDescription() {
        val m = LinkPreviews.parse(page("bbc_news.html"), "https://www.bbc.com/news")!!
        assertEquals("BBC News - Breaking news, video and the latest top stories from the U.S. and around the world", m.title)
        assertTrue(m.desc.startsWith("Visit BBC News for the latest news"))
        assertNull(m.image)
    }

    @Test fun ogTagsInEitherOrderAndNameOrProperty() {
        val html = """<html><head><title>Fallback</title>
            <meta content="The Title" property="og:title">
            <meta name="description" content="Plain description">
            <meta property="og:image" content="/img/card.png?a=1&amp;b=2">
            </head><body></body></html>"""
        val m = LinkPreviews.parse(html, "https://example.com/post/1")!!
        assertEquals("The Title", m.title)
        assertEquals("Plain description", m.desc)
        // Entity-decoded and made absolute against the page (the x.com &amp; case).
        assertEquals("https://example.com/img/card.png?a=1&b=2", m.image)
    }

    @Test fun twitterAndTitleTagAreFallbacks() {
        val html = """<head><meta name="twitter:title" content="Tw title"><meta name="twitter:description" content="Tw desc">
            <meta name="twitter:image" content="https://cdn.example.com/x.jpg"></head>"""
        val m = LinkPreviews.parse(html, "https://example.com")!!
        assertEquals(LinkPreviews.Meta("Tw title", "Tw desc", "https://cdn.example.com/x.jpg"), m)
        assertEquals("Only title", LinkPreviews.parse("<title> Only title </title>", "https://a.com")!!.title)
        assertNull(LinkPreviews.parse("<head><meta name=\"x\" content=\"y\"></head>", "https://a.com"))
    }

    @Test fun pageCannotAimTheImageInwardOrPlain() {
        val inward = """<meta property="og:title" content="T"><meta property="og:image" content="https://192.168.1.1/a.png">"""
        assertNull(LinkPreviews.parse(inward, "https://a.com")!!.image)
        val plain = """<meta property="og:title" content="T"><meta property="og:image" content="http://a.com/a.png">"""
        assertNull(LinkPreviews.parse(plain, "https://a.com")!!.image)
    }

    @Test fun entitiesNumericThenNamed() {
        assertEquals("22K views · 988 reactions", LinkPreviews.decodeEntities("22K views &#xb7; 988 reactions"))
        assertEquals("A & B \"c\" 'd' <e> ·", LinkPreviews.decodeEntities("A &amp; B &quot;c&quot; &apos;d&apos; &lt;e&gt; &#183;"))
    }

    @Test fun firstHttpsLinkOnly() {
        assertEquals("https://example.com/a?b=1", LinkPreviews.firstUrl("look at https://example.com/a?b=1."))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", LinkPreviews.firstUrl("(see https://en.wikipedia.org/wiki/Foo_(bar))"))
        assertNull(LinkPreviews.firstUrl("http://example.com plain http"))
        // Typed without a scheme it previews as https (iOS 9332362b).
        assertEquals("https://www.example.com", LinkPreviews.firstUrl("www.example.com no scheme"))
        assertNull(LinkPreviews.firstUrl("no link here"))
        assertEquals("https://b.com", LinkPreviews.firstUrl("http://a.com then https://b.com"))
    }

    @Test fun youtubeIds() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, LinkPreviews.youtubeId("https://www.youtube.com/watch?v=$id&t=10"))
        assertEquals(id, LinkPreviews.youtubeId("https://youtu.be/$id"))
        assertEquals(id, LinkPreviews.youtubeId("https://m.youtube.com/shorts/$id"))
        assertEquals(id, LinkPreviews.youtubeId("https://youtube.com/embed/$id"))
        assertNull(LinkPreviews.youtubeId("https://youtube.com/watch?v=short"))
        assertNull(LinkPreviews.youtubeId("https://notyoutube.com/watch?v=$id"))
    }

    @Test fun ourOwnAndPrivateLinksAreNeverFetched() {
        assertTrue(LinkPreviews.isCallLink("https://call.fariin.com/video/abc"))
        assertTrue(LinkPreviews.isCallLink("https://fariin.com/voice/abc"))
        assertFalse(LinkPreviews.isCallLink("https://fariin.com/u/dave"))
        assertTrue(LinkPreviews.isOwnLink("https://fariin.com/u/dave"))
        assertTrue(LinkPreviews.isOwnLink("https://group.fariin.com/abc"))
        assertFalse(LinkPreviews.isOwnLink("https://notfariin.com/"))
        for (u in listOf("https://localhost/", "https://10.0.0.1/", "https://172.20.1.1/", "https://100.64.0.1/",
            "https://printer.local/", "https://[::1]/", "https://[fd00::1]/", "https://169.254.1.1/")) {
            assertTrue(u, LinkPreviews.isPrivateHost(u))
        }
        assertFalse(LinkPreviews.isPrivateHost("https://8.8.8.8/"))
        assertFalse(LinkPreviews.isPrivateHost("https://example.com/"))
    }

    @Test fun readStopsAtEndOfHead() {
        val s = "<html><HEAD><title>x</title></Head><body>"
        val b = s.toByteArray()
        assertEquals(s.indexOf("</Head>") + 7, LinkPreviews.headEnd(b, b.size))
        assertEquals(-1, LinkPreviews.headEnd("<head><title>".toByteArray(), 13))
        // A tag split across two chunks is still found from the second chunk's start.
        assertEquals(s.indexOf("</Head>") + 7, LinkPreviews.headEnd(b, b.size, s.indexOf("</Head>") + 3))
        assertEquals(-1, LinkPreviews.headEnd(b, b.size, s.indexOf("<body>")))
    }

    @Test fun privacyOffMeansNoCard() {
        val t = "see https://example.com/a"
        assertNull(LinkPreviews.cardUrlFor(t, enabled = false, suppressed = null))
        assertEquals("https://example.com/a", LinkPreviews.cardUrlFor(t, enabled = true, suppressed = null))
        // The X on the card: that link goes bare.
        assertNull(LinkPreviews.cardUrlFor(t, enabled = true, suppressed = "https://example.com/a"))
        assertNull(LinkPreviews.cardUrlFor("no link", enabled = true, suppressed = null))
    }

    @Test fun noCardOnTheKnock() {
        val me = "me"
        assertFalse(LinkPreviews.allowedIn(Conversation("a_me", startedBy = me, accepted = false), me))
        assertTrue(LinkPreviews.allowedIn(Conversation("a_me", startedBy = me, accepted = true), me))
        assertTrue(LinkPreviews.allowedIn(Conversation("a_me", startedBy = me, accepted = false, lastSender = "a"), me))
        assertTrue(LinkPreviews.allowedIn(null, me))
    }

    @Test fun fieldMapIsTheIosShape() = runBlocking {
        val seal: suspend (String) -> String? = { "enc1:$it" }
        val meta = EncMeta(v = 1, n = "N", k = "K", kn = "KN")
        val d = LinkPreviews.fields("https://a.com", "Title", "", "https://storage/x-lp.enc" to meta, seal)!!
        assertEquals(setOf("url", "title", "imageUrl", "imageEnc"), d.keys)   // empty desc is left out
        assertEquals("enc1:https://a.com", d["url"])
        assertEquals("enc1:Title", d["title"])
        assertEquals("https://storage/x-lp.enc", d["imageUrl"])
        assertEquals(meta.asMap(), d["imageEnc"])
        val bare = LinkPreviews.fields("https://a.com", "T", "D", null, seal)!!
        assertEquals(setOf("url", "title", "desc"), bare.keys)
        // A url that cannot be sealed sends no card at all.
        assertNull(LinkPreviews.fields("https://a.com", "T", "D", null) { null })
    }

    @Test fun reelAndShortVideoAddresses() {
        assertTrue(LinkPreviews.isVideoLink("https://www.instagram.com/reel/Cabc123/"))
        assertTrue(LinkPreviews.isVideoLink("https://instagram.com/reels/Cabc123"))
        assertTrue(LinkPreviews.isVideoLink("https://www.instagram.com/tv/Cabc123"))
        assertFalse(LinkPreviews.isVideoLink("https://www.instagram.com/p/Cabc123"))
        assertTrue(LinkPreviews.isVideoLink("https://www.tiktok.com/@someone/video/7300000000000000000"))
        assertTrue(LinkPreviews.isVideoLink("https://vm.tiktok.com/ZMabc/"))
        assertTrue(LinkPreviews.isVideoLink("https://vt.tiktok.com/ZSabc/"))
        assertFalse(LinkPreviews.isVideoLink("https://www.tiktok.com/@someone"))
        assertFalse(LinkPreviews.isVideoLink("https://example.com/reel/1"))
        assertTrue(LinkPreviews.isBrandTitle("https://www.instagram.com/reel/x", "Instagram"))
        assertTrue(LinkPreviews.isBrandTitle("https://www.tiktok.com/@a/video/1", "TikTok - Make Your Day"))
        assertFalse(LinkPreviews.isBrandTitle("https://www.tiktok.com/@a/video/1", "A dance"))
    }

    @Test fun ogTypeVideoMarksTheDraft() {
        val html = """<head><meta property="og:title" content="Clip"><meta property="og:type" content="video.other"></head>"""
        assertTrue(LinkPreviews.parse(html, "https://example.com/c")!!.video)
        assertFalse(LinkPreviews.parse("""<head><meta property="og:title" content="Page"></head>""", "https://example.com/p")!!.video)
    }

    @Test fun tallCardShapeAndDefault() {
        assertEquals(S.LARGE, S.COMPACT.next()); assertEquals(S.POSTER, S.LARGE.next()); assertEquals(S.COMPACT, S.POSTER.next())
        // 4:5 to 2:3, clamped; a video with no picture keeps a square; anything else none.
        assertEquals(375f, TextStoryRules.posterPictureHeight(300f, 1000, 1000, false), 0.01f)
        assertEquals(450f, TextStoryRules.posterPictureHeight(300f, 500, 1000, false), 0.01f)
        assertEquals(400f, TextStoryRules.posterPictureHeight(300f, 600, 800, false), 0.01f)
        assertEquals(300f, TextStoryRules.posterPictureHeight(300f, 0, 0, true), 0.01f)
        assertEquals(0f, TextStoryRules.posterPictureHeight(300f, 0, 0, false), 0.01f)
        assertEquals(450f + 92f + 10f + 30f,
            TextStoryRules.linkCardHeight(300f, S.POSTER, true, 500, 1000, true, true), 0.01f)
        assertEquals(220f, TextStoryRules.linkCardHeight(300f, S.LARGE, true, 0, 0, false, true), 0.01f)
        assertEquals(S.POSTER, TextStoryRules.preferredLinkStyle(false, true, 0, 0))
        assertEquals(S.POSTER, TextStoryRules.preferredLinkStyle(false, false, 600, 1000))
        assertEquals(S.COMPACT, TextStoryRules.preferredLinkStyle(false, false, 1200, 630))
        assertEquals(S.COMPACT, TextStoryRules.preferredLinkStyle(true, true, 600, 1000))
    }

    @Test fun bareLinkWords() {
        assertTrue(TextStoryRules.isBareLink("  https://www.instagram.com/reel/abc  "))
        assertFalse(TextStoryRules.isBareLink("look https://www.instagram.com/reel/abc/"))
        assertFalse(TextStoryRules.isBareLink("just words"))
    }
}
