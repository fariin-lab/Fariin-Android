package com.fariin.app.data

import com.fariin.app.data.LocationShare.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationShareTest {
    @Test fun approximateRoundsToTheGridAndDropsTheStreet() {
        val s = LocationShare.outgoing(9.56123, 44.06789, exact = false, name = "Main Street 12", area = "Hargeisa")
        assertEquals(9.56, s.lat, 0.0)
        assertEquals(44.07, s.lon, 0.0)
        assertEquals("Near Hargeisa", s.label)
        // The wire text is plain short numbers, which iOS reads as it reads any share.
        assertEquals("fariin-location:9.56|44.07|Near Hargeisa", CardRules.locationText(s.lat, s.lon, s.label))
        // No town came back: iOS's own words.
        assertEquals("Approximate location", LocationShare.outgoing(1.0, 2.0, false, "x", null).label)
        assertEquals("Approximate location", LocationShare.outgoing(1.0, 2.0, false, "x", "  ").label)
        assertEquals(-1.56, LocationShare.roundApprox(-1.5649), 0.0)
        assertEquals(180.0, LocationShare.outgoing(0.0, 179.999, false, null, null).lon, 0.0)
    }

    @Test fun exactKeepsTheSpotAndItsName() {
        val s = LocationShare.outgoing(52.3676, 4.9041, exact = true, name = " Dam Square ", area = "Amsterdam")
        assertEquals(LocationShare.Spot(52.3676, 4.9041, "Dam Square"), s)
        assertNull(LocationShare.outgoing(52.3676, 4.9041, true, " ", "Amsterdam").label)
    }

    @Test fun areaIsTheTownNeverTheStreet() {
        assertEquals("Mogadishu", LocationShare.areaName("Mogadishu", "Banaadir", "Banaadir", "Somalia"))
        assertEquals("Banaadir", LocationShare.areaName(null, "Banaadir", null, "Somalia"))
        assertEquals("Somalia", LocationShare.areaName(" ", null, null, "Somalia"))
        assertNull(LocationShare.areaName(null, null, null, null))
    }

    @Test fun accuracyNeverPromisesMoreThanTheFix() {
        assertEquals("Within 27 m", LocationShare.accuracyText(26.3f))
        assertEquals("Within 5 m", LocationShare.accuracyText(5f))
        assertEquals("Within 130 m", LocationShare.accuracyText(121f))
        assertEquals("Within 1.3 km", LocationShare.accuracyText(1250f))
        assertNull(LocationShare.accuracyText(null))
        assertNull(LocationShare.accuracyText(0f))
        assertNull(LocationShare.accuracyText(Float.NaN))
    }

    @Test fun pinNoteSaysWhereTheSpotCameFrom() {
        assertEquals("Finding your location…", LocationShare.pinNote(Source.MY_FIX, null, false, hasFix = false))
        assertEquals("Within 27 m", LocationShare.pinNote(Source.MY_FIX, 26.3f, false, true))
        assertEquals("Approximate location", LocationShare.pinNote(Source.MY_FIX, 1500f, true, true))
        assertEquals("Your location", LocationShare.pinNote(Source.MY_FIX, null, false, true))
        assertEquals("Spot chosen on the map", LocationShare.pinNote(Source.MAP, 5f, false, true))
        assertEquals("Place chosen from the list", LocationShare.pinNote(Source.PLACE, 5f, true, true))
    }

    @Test fun onlyMyOwnCoarseFixIsForcedApproximate() {
        assertTrue(LocationShare.mustBeApproximate(Source.MY_FIX, coarseOnly = true))
        assertFalse(LocationShare.mustBeApproximate(Source.MY_FIX, coarseOnly = false))
        assertFalse(LocationShare.mustBeApproximate(Source.MAP, coarseOnly = true))
        assertFalse(LocationShare.mustBeApproximate(Source.PLACE, coarseOnly = true))
    }

    @Test fun approximateCardsAreRecognised() {
        assertTrue(LocationShare.looksApproximate(CardRules.Location(9.56, 44.07, "Near Hargeisa")))
        assertTrue(LocationShare.looksApproximate(CardRules.Location(9.5612, 44.0712, "Approximate location")))
        assertFalse(LocationShare.looksApproximate(CardRules.Location(9.5612, 44.0712, "Main Street")))
        assertFalse(LocationShare.looksApproximate(CardRules.Location(9.56, 44.0712, null)))
    }

    @Test fun cardZoom() {
        assertEquals(16.0, LocationShare.cardZoom(false), 0.0)
        assertEquals(13.0, LocationShare.cardZoom(true), 0.0)
    }

    @Test fun aBetterOrNewerFixWins() {
        assertTrue(LocationShare.takeNewFix(null, null, 900f))
        assertTrue(LocationShare.takeNewFix(30f, 2_000L, 12f))
        assertFalse(LocationShare.takeNewFix(12f, 2_000L, 900f))
        assertFalse(LocationShare.takeNewFix(12f, 2_000L, null))
        assertTrue(LocationShare.takeNewFix(12f, 11_000L, 900f))
    }

    @Test fun distance() {
        // One hundredth of a degree of latitude is about 1.11 km.
        assertEquals(1112.0, LocationShare.distanceM(0.0, 0.0, 0.01, 0.0), 2.0)
        assertEquals(0.0, LocationShare.distanceM(9.5, 44.0, 9.5, 44.0), 0.0)
    }
}
