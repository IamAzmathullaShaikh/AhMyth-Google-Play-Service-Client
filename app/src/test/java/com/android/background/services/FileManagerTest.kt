package com.android.background.services

import com.android.background.services.helpers.FileManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerTest {

    @Test
    fun testFileSizeFormatter() {
        assertEquals("?", FileManager.fileSizeFormatter(0))
        assertEquals("1 KB", FileManager.fileSizeFormatter(1024))
        assertEquals("1 MB", FileManager.fileSizeFormatter(1024 * 1024))
    }
}
