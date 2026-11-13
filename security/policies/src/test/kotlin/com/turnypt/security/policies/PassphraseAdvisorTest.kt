package com.turnypt.security.policies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.log2

/**
 * v0.4.0: the passphrase advisor is pure JVM logic — pinned here so the
 * wordlist can never silently drift and the meter's rules stay explainable.
 */
class PassphraseAdvisorTest {

    // ---------------------------------------------------------- wordlist

    @Test
    fun `wordlist is exactly 512 entries`() {
        // 9 bits of choice per word is what GUARANTEED_BITS is computed
        // from; uniqueness and the 3–8 letter shape are re-verified by the
        // repository deep-scan audit against the source itself.
        assertEquals(512, PassphraseAdvisor.WORD_COUNT)
        assertEquals(69, PassphraseAdvisor.GUARANTEED_BITS)
    }

    @Test
    fun `GUARANTEED_BITS is floor(7 log2 512 + log2 100)`() {
        val expected = floor(7 * log2(512.0) + log2(100.0)).toInt()
        assertEquals(expected, PassphraseAdvisor.GUARANTEED_BITS)
        assertTrue(PassphraseAdvisor.GUARANTEED_BITS >= 60)
    }

    // ---------------------------------------------------------- generator

    @Test
    fun `recommend matches word-word hyphen shape with ascii digits`() {
        val shape = Regex("^([a-z]{3,8}-){7}[0-9]{2}$")
        repeat(50) {
            val suggestion = PassphraseAdvisor.recommend(SecureRandom())
            assertTrue("bad shape: $suggestion", shape.matches(suggestion))
            // Long enough for the policy floor and comfortably typeable.
            assertTrue(suggestion.length >= 12 && suggestion.length <= 96)
        }
    }

    @Test
    fun `recommend produces distinct suggestions`() {
        val seen = (1..20).map { PassphraseAdvisor.recommend() }.toSet()
        // 2^69 space: collisions are astronomically unlikely by design.
        assertTrue("only ${seen.size} distinct suggestions", seen.size > 1)
    }

    @Test
    fun `a recommendation is strong in the live meter`() {
        // The generator's output must never read as weak/fair under the
        // same meter the user sees while typing their own choice.
        assertTrue(
            PassphraseAdvisor.strength(PassphraseAdvisor.recommend())
                == PassphraseAdvisor.Strength.STRONG,
        )
    }

    // ---------------------------------------------------------- meter

    @Test
    fun `empty and too-short passphrases are weak`() {
        assertEquals(PassphraseAdvisor.Strength.WEAK, PassphraseAdvisor.strength(""))
        assertEquals(PassphraseAdvisor.Strength.WEAK, PassphraseAdvisor.strength("letmein"))
    }

    @Test
    fun `single class and repeated runs are weak`() {
        // 8 x 'a': one class + identical run.
        assertEquals(PassphraseAdvisor.Strength.WEAK, PassphraseAdvisor.strength("aaaaaaaa"))
        // Digits only + the 1234/5678 runs.
        assertEquals(PassphraseAdvisor.Strength.WEAK, PassphraseAdvisor.strength("12345678"))
    }

    @Test
    fun `the classic capital plus digit is fair not strong`() {
        // The 8-character "Correct1" pattern users default to: two full
        // character classes short of the honest 60-bit bar.
        assertEquals(PassphraseAdvisor.Strength.FAIR, PassphraseAdvisor.strength("Correct1"))
    }

    @Test
    fun `a sequence penalty can drop fair to weak`() {
        // Without the 1234 sequence this would be fair (47 bits); the
        // penalty (−10) takes it below the 40-bit weak bar.
        assertEquals(PassphraseAdvisor.Strength.WEAK, PassphraseAdvisor.strength("Abc12345"))
    }

    @Test
    fun `long multi-word phrases are strong`() {
        assertEquals(
            PassphraseAdvisor.Strength.STRONG,
            PassphraseAdvisor.strength("correct horse battery staple 2"),
        )
    }

    @Test
    fun `estimateBits is zero for empty and floors at zero`() {
        assertEquals(0, PassphraseAdvisor.estimateBits(""))
        // 4 x 'a': raw 18 bits − 10 (single class) − 10 (identical run) = −2,
        // floored at zero by coerceAtLeast(0) — this is the floor case.
        // (Five spaces would NOT reach the floor: 25 − 20 = 5.)
        assertEquals(0, PassphraseAdvisor.estimateBits("aaaa"))
    }
}
