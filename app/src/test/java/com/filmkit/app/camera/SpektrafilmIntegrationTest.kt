package com.filmkit.app.camera

import com.filmkit.app.ui.CameraPresets
import com.filmkit.app.ui.slotActiveInEngine
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SpektrafilmIntegrationTest {
    @Test fun spectralScopePreservesSensorFrontChainAndCaller() {
        val p = CameraPresets.standard().also { it[98] = 2.0 }
        val sensor = setOf(0, 1, 2, 3, 45, 46, 48, 49, 50, 51, 52, 54, 55, 56, 57, 58, 59, 88, 89, 101, 104)
        for (slot in sensor) p[slot] = 0.123 + slot
        val before = p.copyOf()
        val out = engineScoped(p)
        assertNotSame(p, out)
        assertArrayEquals(before, p, 0.0)
        for (slot in sensor) {
            assertEquals("sensor slot $slot", before[slot], out[slot], 0.0)
            assertTrue("sensor control $slot", slotActiveInEngine(slot, 2))
        }
        for (slot in listOf(4, 26, 34, 39, 62, 66, 69, 90, 95, 99, 100))
            assertFalse("legacy control $slot", slotActiveInEngine(slot, 2))
        assertEquals(0.0, out[26], 0.0)
        assertEquals(0.0, out[34], 0.0)
        assertEquals(0.0, out[39], 0.0)
        assertEquals(0.0, out[62], 0.0)
        assertEquals(0.0, out[99], 0.0)
        assertEquals(2.0, out[98], 0.0)
    }

    @Test fun displayEditorDoesNotRunSceneEngineOrDisableItsOwnAdjustments() {
        val p = CameraPresets.standard().also { it[98] = 2.0; it[21] = 1.23; it[26] = 4.5; it[39] = 0.4 }
        val out = EngineScope.applyEngineScopeForEditor(p, 2)
        for (slot in listOf(21, 26, 39)) assertEquals(p[slot], out[slot], 0.0)
        assertEquals(0.0, out[35], 0.0)
        assertEquals(0.0, out[41], 0.0)
    }

    @Test fun packagedProfileAndMetadataHaveTheFrozenDigest() {
        val asset = File("src/main/assets/${SpektrafilmAssets.ASSET}")
        assertEquals(931048L, asset.length())
        val hash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(SpektrafilmAssets.SHA256, hash)
        assertTrue(File("src/main/assets/spektrafilm/manifest.json").readText().contains(hash))
    }

    @Test fun rawNativeBranchIsAfterDenoiseAndBeforeLegacyFilmAndOutput() {
        val src = File("../core/engine/src/render/FrameRenderer.cpp").readText()
        val chain = src.substringAfter("void FrameRenderer::finishStillChain(")
            .substringBefore("void FrameRenderer::", "")
        val branch = chain.indexOf("renderSpektrafilm(c, film_src, out_rgba)")
        assertTrue(branch > chain.indexOf("passPc3d("))
        assertTrue(branch > chain.indexOf("const GLuint film_src"))
        assertTrue(branch < chain.indexOf("passFilm(dst_w, dst_h"))
        assertTrue(chain.substring(branch, chain.indexOf("passFilm(dst_w, dst_h")).contains("return;"))
    }
}
