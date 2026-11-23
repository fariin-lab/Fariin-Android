package com.fariin.app.data

/**
 * Which photo a profile link card shows (L-80, the design agreed with iOS). A profile card
 * (`fariin.com/u/<handle>`) carries NO image: the sender writes none, and any image or photoThumb an
 * old card still holds is ignored. The photo is the one THIS viewer's own profile read of that
 * person gives ([PeopleApi.profile], which applies their photo privacy), and none when I blocked them.
 * Every other link card keeps the picture its sender sealed in.
 */
object ProfileCardPhoto {
    /** Whether the sender puts a picture on the card for this link. */
    fun senderCarriesImage(isProfileCard: Boolean): Boolean = !isProfileCard

    /**
     * The photo to draw: for a profile card only [profileReadPhoto] (null while unknown, hidden or
     * blocked), never [cardImage]; for any other card its own [cardImage].
     */
    fun shown(isProfileCard: Boolean, cardImage: String?, profileReadPhoto: String?, blockedByMe: Boolean): String? =
        if (!isProfileCard) cardImage
        else if (blockedByMe) null
        else profileReadPhoto?.ifEmpty { null }
}
