package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonPlacesTest {
    @Test fun searchUrlEncodesTheWordsAndBiasesCoarsely() {
        assertEquals("https://photon.komoot.io/api?q=caf%C3%A9+%26+bar&limit=12&lat=9.561&lon=44.068",
            PhotonPlaces.searchUrl(" café & bar ", 9.56123, 44.06789))
        assertEquals("https://photon.komoot.io/api?q=x&limit=5", PhotonPlaces.searchUrl("x", null, null, 5))
    }

    @Test fun nearbyAndReverseUrls() {
        assertEquals("https://photon.komoot.io/reverse?lat=52.3700&lon=4.8900&radius=0.8&limit=25" +
            "&osm_tag=amenity&osm_tag=shop&osm_tag=tourism&osm_tag=leisure&osm_tag=historic",
            PhotonPlaces.nearbyUrl(52.37, 4.89))
        assertEquals("https://photon.komoot.io/reverse?lat=-1.50000&lon=36.25000&limit=1", PhotonPlaces.reverseUrl(-1.5, 36.25))
    }

    private val sample = """
        {"type":"FeatureCollection","features":[
         {"type":"Feature","properties":{"name":"Coffee Roasters","housenumber":"30-H","street":"Huidenstraat",
           "locality":"De 9 Straatjes","city":"Amsterdam","country":"Nederland","osm_key":"shop"},
          "geometry":{"type":"Point","coordinates":[4.885134,52.3688809]}},
         {"type":"Feature","properties":{"name":"Coffee Roasters","street":"Huidenstraat","city":"Amsterdam"},
          "geometry":{"type":"Point","coordinates":[4.88513,52.36888]}},
         {"type":"Feature","properties":{"housenumber":"17","street":"Begijnhof","district":"Centrum","city":"Amsterdam","country":"Nederland"},
          "geometry":{"type":"Point","coordinates":[4.8901289,52.3698533]}},
         {"type":"Feature","properties":{"name":"No point"},"geometry":{"type":"Point","coordinates":[]}},
         {"type":"Feature","properties":{"name":"Bad"},"geometry":{"type":"Point","coordinates":[200.0,95.0]}},
         {"type":"Feature","properties":{"state":"Woqooyi Galbeed","country":"Somaliland"},
          "geometry":{"type":"Point","coordinates":[44.06,9.56]}}
        ]}
    """.trimIndent()

    @Test fun parsesNamesAddressesAndArea() {
        val got = PhotonPlaces.parse(sample)
        assertEquals(3, got.size)
        assertEquals(PhotonPlaces.Place("Coffee Roasters", "Huidenstraat 30-H, De 9 Straatjes, Amsterdam, Nederland",
            52.3688809, 4.885134, "Amsterdam"), got[0])
        // No name: the street and number stand in, the address keeps the rest.
        assertEquals("Begijnhof 17", got[1].name)
        assertEquals("Centrum, Amsterdam, Nederland", got[1].address)
        // Only a region: it is the name and the area.
        assertEquals("Woqooyi Galbeed", got[2].name)
        assertEquals("Woqooyi Galbeed", got[2].area)
    }

    @Test fun nearbyKeepsOnlyNamedPlaces() {
        val got = PhotonPlaces.parse(sample, namedOnly = true)
        assertEquals(listOf("Coffee Roasters"), got.map { it.name })
    }

    @Test fun brokenAnswersGiveNothing() {
        assertTrue(PhotonPlaces.parse("not json").isEmpty())
        assertTrue(PhotonPlaces.parse("{}").isEmpty())
        assertEquals("Fariin-Android", PhotonPlaces.USER_AGENT)
    }
}
