package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchSuggestTest {
    private val names = listOf("Abdi Kulan", "Hodan", "Family Group", "Mohamed Ali")

    @Test fun oneSlipInAShortWordFindsTheName() {
        assertEquals("Hodan", SearchSuggest.closest("hodn", names))
    }

    @Test fun twoSlipsInALongerWordFindTheName() {
        assertEquals("Mohamed Ali", SearchSuggest.closest("mohmmed", names))
    }

    @Test fun aSecondWordOfTheNameCounts() {
        assertEquals("Abdi Kulan", SearchSuggest.closest("kulna", names))
    }

    @Test fun twoSlipsInAShortSearchIsTooFar() {
        assertNull(SearchSuggest.closest("hxdx", names))
    }

    @Test fun fewerThanThreeLettersSuggestsNothing() {
        assertNull(SearchSuggest.closest("ho", names))
    }

    @Test fun aSearchAlreadyInANameSuggestsNothing() {
        assertNull(SearchSuggest.closest("famil", names))
    }

    @Test fun caseAndAccentsAreIgnored() {
        assertEquals("José", SearchSuggest.closest("JOSS", listOf("José")))
    }

    @Test fun theCloserNameWins() {
        assertEquals("Amjna", SearchSuggest.closest("amina", listOf("Amiir", "Amjna")))
    }

    @Test fun unrelatedSearchSuggestsNothing() {
        assertNull(SearchSuggest.closest("zzzzzz", names))
    }
}
