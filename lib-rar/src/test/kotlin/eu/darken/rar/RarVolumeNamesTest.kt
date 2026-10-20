package eu.darken.rar

import eu.darken.rar.RarVolumeNames.volumeName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RarVolumeNamesTest {
    @Test
    fun `part naming keeps the first name's digit width`() {
        assertEquals("a.part1.rar", volumeName("a.part1.rar", 0))
        assertEquals("a.part2.rar", volumeName("a.part1.rar", 1))
        assertEquals("a.part10.rar", volumeName("a.part1.rar", 9))
        assertEquals("a.part02.rar", volumeName("a.part01.rar", 1))
        assertEquals("a.part10.rar", volumeName("a.part01.rar", 9))
        assertEquals("a.part100.rar", volumeName("a.part01.rar", 99))
        assertEquals("a.part002.rar", volumeName("a.part001.rar", 1))
        assertEquals("My Archive.v2.PART3.RAR", volumeName("My Archive.v2.PART1.RAR", 2))
    }

    @Test
    fun `legacy naming continues with r00, r01 and then s00`() {
        assertEquals("a.rar", volumeName("a.rar", 0))
        assertEquals("a.r00", volumeName("a.rar", 1))
        assertEquals("a.r01", volumeName("a.rar", 2))
        assertEquals("a.r99", volumeName("a.rar", 100))
        assertEquals("a.s00", volumeName("a.rar", 101))
        assertEquals("A.R05", volumeName("A.RAR", 6))
        assertEquals("x.part.r00", volumeName("x.part.rar", 1))
        assertEquals("vol-legacy.r01", volumeName("vol-legacy.rar", 2))
    }

    @Test
    fun `names that cannot be derived`() {
        assertNull(volumeName("a.part2.rar", 1))
        assertNull(volumeName("a.part0.rar", 1))
        assertNull(volumeName("a.zip", 1))
        assertNull(volumeName("a.r00", 1))
        assertNull(volumeName("a.rar", -1))
        assertNull(volumeName("a.rar", 1 + 900))
        assertEquals("a.z99", volumeName("a.rar", 900))
    }

    @Test
    fun `fixture volume sets follow the scheme`() {
        for (fixture in Fixtures.rar723.fixtures.values.filter { it.isVolumeSet }) {
            fixture.files.forEachIndexed { index, file ->
                assertEquals(file, volumeName(fixture.files.first(), index), fixture.name)
            }
        }
    }
}
