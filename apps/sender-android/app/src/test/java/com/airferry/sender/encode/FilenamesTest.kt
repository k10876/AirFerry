package com.airferry.sender.encode

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenamesTest {
    @Test
    fun stripsPathAndIllegalChars() {
        assertEquals("a_b.txt", Filenames.sanitize("a:b.txt"))
        assertEquals("report.pdf", Filenames.sanitize("/tmp/foo/report.pdf"))
        assertEquals("unnamed", Filenames.sanitize("   "))
    }

    @Test
    fun normalizesTextNameToTxt() {
        assertEquals("unnamed.txt", Filenames.normalizeTxt(""))
        assertEquals("hello.txt", Filenames.normalizeTxt("hello"))
        assertEquals("note.TXT", Filenames.normalizeTxt("note.TXT"))
    }
}
