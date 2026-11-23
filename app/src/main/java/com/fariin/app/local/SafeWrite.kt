package com.fariin.app.local

import java.io.File
import java.io.FileOutputStream

/**
 * Whole-file writes that never leave a half file (audit L-83): the new bytes go to a temp file,
 * are flushed to the disk, and then take the old file's name in one rename. A crash or a full disk
 * leaves the old file as it was.
 */
object SafeWrite {
    /** [f] holds [bytes] afterwards, or (on any failure) still holds what it held before. */
    fun replace(f: File, bytes: ByteArray): Boolean {
        val dir = f.parentFile ?: return false
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
            if (tmp.renameTo(f)) true else { tmp.delete(); false }
        } catch (_: Exception) { tmp.delete(); false }
    }

    /**
     * A file that exists but could not be opened is moved aside (name + ".unread"), never written
     * over: the next save starts a new file and the old one is still on the disk.
     */
    fun setAside(f: File) {
        if (!f.exists()) return
        val aside = File(f.parentFile, f.name + ".unread")
        runCatching { aside.delete(); f.renameTo(aside) }
    }
}
