package com.peterlaurence.trekme.core.mapsource.wmts

import org.junit.Assert
import org.junit.Test

class IgnTest {
    @Test
    fun tilesTest() {
        val p1 = Point(275951.78, 6241946.52)
        val p2 = Point(276951.78, 6240946.52)
        val tileSequence = getTileSequenceAndCalibration(18, 18, p1, p2).tileSequence

        val firstTile = tileSequence.first()
        Assert.assertEquals(132877, firstTile.col)
        Assert.assertEquals(90241, firstTile.row)

        val lastTile = tileSequence.last()
        Assert.assertEquals(132884, lastTile.col)
        Assert.assertEquals(90248, lastTile.row)
    }
}