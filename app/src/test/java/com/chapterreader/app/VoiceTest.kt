package com.chapterreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the Microsoft voice token matches edge-tts exactly, and that a voice really speaks. */
class VoiceTest {
    @Test
    fun tokenMatchesEdgeTts() {
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", Gec.token(1700000000.0))
        assertEquals("DE0FEAEA16F3CA48-CBB7726D7630902BE2E75AB7E36EBCE26C90B77973254CED08D24BE849AE0D6E", Gec.token(1793458451.0))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", Gec.token(1800000123.7))
        assertEquals("E47F243C6E911E2A-E8D1CE0D58FCE1D016F937663C99B9BAE4B3B9E4430419E825A6EE7F0F91D5AD", Gec.token(1762264200.0))
    }

    @Test
    fun avaSpeaks() {
        val r = EdgeTts.synth("Hello, this is the chapter reader.", "en-US-AvaMultilingualNeural", 1.2f)
        println("Ava: " + r.audio.size + " bytes, " + r.words.size + " words")
        assertTrue(r.audio.size > 2000)
        assertTrue(r.words.isNotEmpty())
    }
}
