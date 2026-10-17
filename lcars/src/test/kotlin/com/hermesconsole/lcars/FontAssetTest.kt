package com.hermesconsole.lcars

import java.io.File
import java.io.RandomAccessFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Antonio face must be a bundled asset, resolved with no network at runtime (REQ-19). That is
 * a structural property, so it is asserted structurally: the file is in the module's assets and is
 * a real variable font, and nothing in the module reaches for a downloadable-font provider.
 */
class FontAssetTest {

    @Test
    fun `the antonio face is bundled in the module assets`() {
        assertTrue(ANTONIO.isFile, "missing bundled font asset: ${ANTONIO.path}")
        assertTrue(ANTONIO.length() > 10_000, "font asset looks truncated: ${ANTONIO.length()} bytes")
    }

    @Test
    fun `the bundled face is a variable font carrying the weight axis`() {
        val tables = sfntTables(ANTONIO)
        assertTrue("glyf" in tables, "not a TrueType outline font: $tables")
        assertTrue("fvar" in tables, "not a variable font, so weights cannot be derived: $tables")
    }

    @Test
    fun `no source in the module resolves a font over the network`() {
        val offenders =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { kt ->
                    val src = kt.readText()
                    NETWORK_FONT_MARKERS.any { it in src }
                }
                .map { it.path }
                .toList()
        assertEquals(emptyList<String>(), offenders, "a font must never be fetched at runtime")
    }

    /** Table directory tags of an sfnt (TTF) file. */
    private fun sfntTables(file: File): List<String> =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            val numTables = raf.readUnsignedShort()
            (0 until numTables).map { i ->
                raf.seek(12L + 16L * i)
                val tag = ByteArray(4).also { raf.readFully(it) }
                String(tag, Charsets.US_ASCII)
            }
        }

    private companion object {
        val ANTONIO = File("src/main/assets/fonts/Antonio-Variable.ttf")
        val NETWORK_FONT_MARKERS =
            listOf("GoogleFont", "FontRequest", "http://", "https://", "downloadable")
    }
}
