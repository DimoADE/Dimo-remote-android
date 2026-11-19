package dev.vibex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionAndTimeoutTest {
    @Test
    fun detectsTokenAtCaret() {
        assertEquals(MentionToken(0, 1, ""), mentionTokenAt("@", 1))
        assertEquals(MentionToken(3, 6, "ab"), mentionTokenAt("hi @ab cd", 6).let { it?.copy(end = 6) })
        assertNull(mentionTokenAt("a@b", 3))
        assertNull(mentionTokenAt("@ab cd", 6))
        assertNull(mentionTokenAt("hello", 5))
    }

    @Test
    fun applyReplacesToken() {
        val token = mentionTokenAt("hi @ab", 6)!!
        assertEquals("hi @Bot " to 8, applyMention("hi @ab", token, "Bot"))
    }

    @Test
    fun postTimeoutsAreLonger() {
        assertEquals(60_000, requestReadTimeoutMs("POST", true))
        assertEquals(15_000, requestReadTimeoutMs("GET", true))
        assertEquals(20_000, requestReadTimeoutMs("GET", false))
    }
}
