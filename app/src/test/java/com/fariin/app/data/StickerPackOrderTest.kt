package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerPackOrderTest {
    private fun pack(id: String, vararg s: String) = StickerPackInfo(id, id.uppercase(), s.map { StickerRef(id, it, "", false) })
    private fun over(id: String, order: Int, enabled: Boolean = true, title: String = "", hide: List<String> = emptyList()) =
        StickerCatalog.RemotePack(id, "bundled", title, order, enabled, false, null, hide.map { StickerCatalog.RemotePack.Item(it, null, null, null, null, true) })

    @Test fun panelOrderAsIos() {
        val shipped = listOf(pack("kulu", "a"), pack("hearts", "b"), pack("topi", "c"))
        // No overrides: the shipped order.
        assertEquals(listOf("kulu", "hearts", "topi"), StickerCatalog.arrange(shipped, emptyList()).map { it.id })
        // The panel moves Topi first, turns Hearts off, renames Kulu and hides nothing else.
        val out = StickerCatalog.arrange(shipped, listOf(over("topi", -1), over("hearts", 0, enabled = false), over("kulu", 5, title = "Kulu!")))
        assertEquals(listOf("topi", "kulu"), out.map { it.id })
        assertEquals("Kulu!", out[1].title)
    }

    @Test fun hiddenStickersAndServerPacks() {
        val shipped = listOf(pack("kulu", "a", "b"))
        val server = StickerCatalog.RemotePack("new", "server", "New", -2, true, false, null, listOf(
            StickerCatalog.RemotePack.Item("x", "😀", "webp", "https://e.test/x.webp", null, null),
            StickerCatalog.RemotePack.Item("y", null, "webp", null, null, null)))   // no link: left out
        val out = StickerCatalog.arrange(shipped, listOf(over("kulu", 1, hide = listOf("a")), server))
        assertEquals(listOf("new", "kulu"), out.map { it.id })
        assertEquals(listOf("x"), out[0].stickers.map { it.stickerId })
        assertEquals(listOf("b"), out[1].stickers.map { it.stickerId })
    }
}
