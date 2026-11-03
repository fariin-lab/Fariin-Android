package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRulesTest {
    @Test fun contactRoundTripsLikeIos() {
        val t = CardRules.contactText("u1", "Bobby | Test", "fariin-photo://profiles/u1.jpg?v=2")
        assertEquals("fariin-contact:u1|fariin-photo://profiles/u1.jpg?v=2|Bobby | Test", t)
        assertEquals(CardRules.Contact("u1", "Bobby | Test", "fariin-photo://profiles/u1.jpg?v=2"), CardRules.contact(t))
        // No photo travels as an empty part.
        assertEquals("fariin-contact:u1||Ann", CardRules.contactText("u1", "Ann", null))
        assertNull(CardRules.contact("fariin-contact:u1||Ann")!!.photo)
    }

    @Test fun contactRefusesWhatIosRefuses() {
        assertNull(CardRules.contact("fariin-contact:|p|Ann"))     // no uid
        assertNull(CardRules.contact("fariin-contact:u1|p|"))      // no name
        assertNull(CardRules.contact("fariin-contact:u1|Ann"))     // two parts
        assertNull(CardRules.contact("hello"))
    }

    @Test fun locationRoundTripsLikeIos() {
        // Exactly what Swift's "\(lat)|\(lon)|\(label ?? "")" writes.
        val t = CardRules.locationText(52.3676, 4.9041, "Dam Square")
        assertEquals("fariin-location:52.3676|4.9041|Dam Square", t)
        assertEquals(CardRules.Location(52.3676, 4.9041, "Dam Square"), CardRules.location(t))
        assertEquals("fariin-location:-1.5|36.25|", CardRules.locationText(-1.5, 36.25, null))
        assertNull(CardRules.location("fariin-location:-1.5|36.25|")!!.label)
        // An older share with no label part at all still reads.
        assertEquals(CardRules.Location(2.0, 45.0, null), CardRules.location("fariin-location:2.0|45.0"))
        // Swift's exponent form ("1e-05") reads too.
        assertEquals(0.00001, CardRules.location("fariin-location:1e-05|0.0|")!!.lat, 0.0)
        // The label is last and may hold a bar.
        assertEquals("A | B", CardRules.location("fariin-location:1.0|2.0|A | B")!!.label)
    }

    @Test fun locationRefusesBadNumbers() {
        assertNull(CardRules.location("fariin-location:abc|2.0|x"))
        assertNull(CardRules.location("fariin-location:1.0"))
        assertNull(CardRules.location("fariin-location:NaN|2.0|"))
        assertNull(CardRules.location("fariin-location:91.0|2.0|"))
        assertNull(CardRules.location("fariin-location:1.0|181.0|"))
    }

    @Test fun previewWords() {
        assertEquals("Contact", CardRules.previewWord("fariin-contact:u1||Ann"))
        assertEquals("Location", CardRules.previewWord("fariin-location:1.0|2.0|"))
        assertNull(CardRules.previewWord("hi"))
        assertNull(CardRules.previewWord(null))
        assertTrue(CardRules.isCard("fariin-location:1.0|2.0|"))
        assertFalse(CardRules.isCard("fariin-pinned:a|b"))
        assertEquals("Location", CardRules.title(CardRules.Location(1.0, 2.0, null)))
        assertEquals("Home", CardRules.title(CardRules.Location(1.0, 2.0, "Home")))
    }

    @Test fun geoLinks() {
        assertEquals("geo:1.5,2.25?q=1.5,2.25", CardRules.geoUri(CardRules.Location(1.5, 2.25, null)))
        assertEquals("geo:1.5,2.25?q=1.5,2.25(Caf%C3%A9%20%26%20Bar%20%28old%29)",
            CardRules.geoUri(CardRules.Location(1.5, 2.25, "Café & Bar (old)")))
        assertEquals("https://www.openstreetmap.org/?mlat=1.5&mlon=2.25#map=16/1.5/2.25",
            CardRules.webUri(CardRules.Location(1.5, 2.25, "x")))
        assertEquals("52.36760, 4.90410", CardRules.coords(52.3676, 4.9041))
    }

    @Test fun placeNameOrder() {
        assertEquals("Central Station", CardRules.placeName("Central Station", "Stationsplein", null, "Amsterdam", null, null, "NL"))
        // A bare house number is no name: the street wins.
        assertEquals("Damrak", CardRules.placeName("12", "Damrak", null, "Amsterdam", null, null, "NL"))
        assertEquals("Amsterdam", CardRules.placeName(null, " ", null, "Amsterdam", null, null, "NL"))
        assertNull(CardRules.placeName(null, null, null, null, null, null, null))
        assertEquals("Damrak, Amsterdam, NL", CardRules.addressLine("Damrak", "Amsterdam", "NL"))
    }

    @Test fun messageButton() {
        val card = CardRules.Contact("b", "Bob", null)
        assertTrue(CardRules.canMessage(card, "a", "a_c"))
        assertFalse(CardRules.canMessage(card, "a", "a_b"))       // already this chat
        assertFalse(CardRules.canMessage(card, "b", "group1"))    // my own card
    }
}
