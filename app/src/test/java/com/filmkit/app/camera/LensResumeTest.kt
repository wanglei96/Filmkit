package com.filmkit.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensResumeTest {
    private val lenses = listOf(
        CameraController.LensInfo("4", 2f, 15.4f, true),
        CameraController.LensInfo("2", 6f, 23.6f, true),
        CameraController.LensInfo("3", 9f, 70.3f, true),
    )

    @Test fun resumeKeepsTeleInsteadOfSilentlyOpeningMain() {
        assertEquals("3", selectOpenLens(lenses, null, "3")?.id)
        assertEquals("4", selectOpenLens(lenses, null, "4")?.id)
    }

    @Test fun explicitSwitchWinsOverPreviousLens() {
        assertEquals("2", selectOpenLens(lenses, "2", "3")?.id)
    }

    @Test fun coldStartAndRemovedLensUseMain() {
        assertEquals("2", selectOpenLens(lenses, null, "")?.id)
        assertEquals("2", selectOpenLens(lenses, null, "gone")?.id)
        assertEquals("3", selectOpenLens(lenses, "gone", "3")?.id)
        assertNull(selectOpenLens(emptyList(), null, "3"))
    }

    @Test fun disabledLensCannotMasqueradeAsRetainedTele() {
        val disabled = lenses.map { if (it.id == "3") it.copy(enabled = false) else it }
        assertEquals("2", selectOpenLens(disabled, "3", "3")?.id)
    }
}
