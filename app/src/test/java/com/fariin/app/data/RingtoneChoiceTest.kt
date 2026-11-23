package com.fariin.app.data

import com.fariin.app.call.RingtoneChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class RingtoneChoiceTest {
    private val ring = "content://settings/system/ringtone"
    private val notif = RingtoneChoice.DEFAULT_NOTIFICATION

    @Test fun pickedSoundRings() = assertEquals("content://media/internal/audio/media/42", RingtoneChoice.pick("content://media/internal/audio/media/42", notif, ring))
    @Test fun startingSoundMeansPhoneRingtone() = assertEquals(ring, RingtoneChoice.pick(notif, notif, ring))
    @Test fun noChannelMeansPhoneRingtone() = assertEquals(ring, RingtoneChoice.pick(null, notif, ring))
    @Test fun emptyMeansPhoneRingtone() = assertEquals(ring, RingtoneChoice.pick("", notif, ring))
}
