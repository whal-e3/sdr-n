package org.satelliteeavesdropper.orbit

import org.junit.Assert.assertEquals
import org.junit.Test

class OrekitSgp4Test {
    @Test fun matchesValladoSatelliteFiveAtEpoch() {
        // Vallado et al., AIAA 2006-6753 Rev. 1, Appendix E, satellite 5 at t=0.
        val omm = valladoOmm()
        val state = OrekitSgp4(omm).at(omm.epoch)
        assertEquals(7_022.46529266, state.position.x, 0.01)
        assertEquals(-1_400.08296755, state.position.y, 0.01)
        assertEquals(0.03995155, state.position.z, 0.01)
        assertEquals(1.893841015, state.velocity.x, 0.0001)
        assertEquals(6.405893759, state.velocity.y, 0.0001)
        assertEquals(4.534807250, state.velocity.z, 0.0001)

        val later = OrekitSgp4(omm).at(omm.epoch.plusSeconds(360 * 60L))
        assertEquals(-7_154.03120202, later.position.x, 0.01)
        assertEquals(-3_783.17682504, later.position.y, 0.01)
        assertEquals(-3_536.19412294, later.position.z, 0.01)
    }
}
