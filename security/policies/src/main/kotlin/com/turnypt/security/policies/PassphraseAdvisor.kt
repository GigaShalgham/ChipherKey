package com.turnypt.security.policies

import java.security.SecureRandom
import java.util.Locale
import kotlin.math.ln

/**
 * v0.4.0 passphrase advisor: a live strength estimate for typed shared
 * passphrases, plus a generator for recommended ones.
 *
 * Two deliberately separate mechanisms:
 *
 *  1. [estimateBits] / [strength] — a CHARACTER-LEVEL heuristic for what the
 *     user types: length, observed character classes, repeated runs and
 *     sequential runs. It can NOT see dictionary words, leetspeak or famous
 *     phrases, so it overestimates those. This is the same honest limitation
 *     [KeyPolicy] documents for its length-only check: the real guidance is
 *     carried by the UI text ("long, random, shared in person") — and the
 *     real fix for a weak choice is the generator below, not the meter.
 *
 *  2. [recommend] — 7 words drawn from [WORDS] (512 entries, 9 bits each)
 *     plus 2 random digits, joined by hyphens. Guaranteed generator entropy:
 *     floor(7·log2(512) + log2(100)) = [GUARANTEED_BITS] bits, on top of the
 *     Argon2id KDF cost an attacker pays per guess. The words are common,
 *     short and unambiguous, so the phrase can be read aloud across a table
 *     and typed on a phone without autocorrect fighting it.
 *
 * Pure Kotlin, no Android dependency — unit-tested in this module.
 */
object PassphraseAdvisor {

    /** Coarse display class for the live meter under a passphrase field. */
    enum class Strength { WEAK, FAIR, STRONG }

    /** Words drawn per recommendation. 7 × 9 bits = 63 bits from the words. */
    private const val WORDS_PER_PASSPHRASE = 7

    /** Two decimal digits 00–99: log2(100) ≈ 6.64 further bits. */
    private const val DIGIT_CHOICES = 100

    /**
     * 512 common, unambiguous, easy-to-spell words (3–8 lowercase letters):
     * 9 bits of choice per word. Curated by hand; the unit test pins the
     * exact size, uniqueness and shape so the list can never silently drift.
     */
    private val WORDS: List<String> = listOf(
        "able", "about", "above", "acid", "acorn", "actor", "adapt", "admit",
        "adopt", "adult", "after", "again", "agent", "agree", "ahead", "alarm",
        "album", "alert", "alien", "alike", "alive", "alley", "almond", "alpha",
        "alter", "amber", "anchor", "angle", "ankle", "annex", "antic", "anvil",
        "apple", "april", "arcade", "arch", "arctic", "arena", "argue", "armor",
        "arrow", "artist", "ascent", "ash", "aspen", "asset", "atlas", "atom",
        "attic", "audio", "august", "aunt", "autumn", "avenue", "awake", "award",
        "axis", "bacon", "badge", "bagel", "baker", "balance", "balcony", "ball",
        "bamboo", "banana", "band", "banjo", "barge", "barn", "baron", "basil",
        "basket", "batch", "baton", "beach", "beacon", "beam", "bean", "bear",
        "beast", "beat", "beauty", "beaver", "bench", "berry", "bicycle", "bill",
        "binder", "birch", "bird", "bison", "blade", "blank", "blast", "blaze",
        "blend", "bliss", "block", "bloom", "blossom", "board", "boat", "bobcat",
        "bolt", "bonus", "book", "booth", "border", "borrow", "bottle", "boulder",
        "bounce", "bowl", "box", "brace", "bracket", "braid", "brain", "branch",
        "brass", "brave", "bread", "brick", "bride", "bridge", "brief", "bright",
        "broad", "brook", "broom", "brush", "bubble", "bucket", "buffalo", "bugle",
        "bundle", "bunker", "bunny", "burst", "cabin", "cable", "cactus", "camel",
        "camera", "canal", "candle", "candy", "canoe", "canvas", "canyon", "capital",
        "captain", "caramel", "cargo", "carpet", "carrot", "castle", "catalog", "cattle",
        "cedar", "cellar", "chalk", "chant", "charm", "chase", "cheese", "chef",
        "cherry", "chess", "chest", "chief", "chill", "chimney", "chorus", "cider",
        "circuit", "citrus", "clamp", "clay", "clerk", "cliff", "climate", "climb",
        "cloak", "clock", "cloud", "clover", "coach", "coast", "cobalt", "cocoa",
        "comet", "compass", "concert", "cone", "copper", "coral", "corner", "cotton",
        "county", "couple", "courage", "coyote", "crack", "crane", "crate", "creek",
        "crest", "cricket", "cross", "crown", "crumb", "crust", "crystal", "cube",
        "curve", "cushion", "cycle", "daisy", "dance", "dawn", "decade", "decor",
        "deer", "delta", "denim", "depot", "desert", "design", "diamond", "diary",
        "diesel", "dime", "dinner", "dish", "dive", "dock", "doctor", "dolphin",
        "dome", "donor", "donut", "dragon", "drama", "dream", "dress", "drift",
        "drum", "duck", "dune", "duplex", "eagle", "earth", "easel", "echo",
        "eclipse", "edge", "editor", "eggplant", "elbow", "elder", "elk", "elm",
        "engine", "enjoy", "entrance", "envelope", "epoch", "era", "essay", "evening",
        "exam", "exit", "extra", "fable", "fabric", "falcon", "family", "fang",
        "farm", "fashion", "fence", "fern", "ferry", "fever", "fiber", "field",
        "fig", "filter", "finger", "fire", "first", "fish", "flag", "flame",
        "flash", "fleet", "flute", "fog", "forest", "fork", "fossil", "fountain",
        "fox", "fraction", "frame", "free", "fresh", "friend", "fringe", "frog",
        "front", "frost", "fruit", "fudge", "funnel", "gadget", "galaxy", "gallery",
        "game", "garage", "garden", "garlic", "gas", "gate", "gather", "gauge",
        "gavel", "gazebo", "gear", "gecko", "gem", "giant", "gift", "ginger",
        "giraffe", "girl", "glacier", "glass", "glaze", "globe", "glove", "glow",
        "glue", "goat", "gold", "goose", "gorge", "gown", "grain", "grand",
        "grape", "graph", "grass", "gravel", "green", "grill", "grin", "grip",
        "grove", "guard", "guitar", "gulf", "guru", "gym", "habit", "hail",
        "hammer", "harbor", "hare", "harvest", "hatch", "hawk", "hazel", "heart",
        "hearth", "hedge", "helmet", "herb", "heron", "hill", "hinge", "honey",
        "hood", "hook", "horn", "horse", "hostel", "hour", "house", "hurdle",
        "hut", "hymn", "iceberg", "icicle", "icon", "idea", "igloo", "image",
        "impact", "inbox", "index", "infant", "inlet", "insect", "iris", "iron",
        "island", "issue", "ivory", "jacket", "jade", "jaguar", "jar", "jasmine",
        "jelly", "jet", "jewel", "jigsaw", "joint", "jolly", "journal", "judge",
        "juice", "jump", "jungle", "juniper", "kayak", "kernel", "kettle", "key",
        "kick", "kidney", "kind", "king", "kiosk", "kitchen", "kite", "kiwi",
        "knee", "knife", "knight", "knit", "koala", "label", "ladder", "lagoon",
        "lake", "lamp", "lance", "lantern", "lap", "larch", "laser", "latch",
        "laurel", "lava", "lawn", "layer", "leaf", "ledge", "legend", "lemon",
        "lens", "leopard", "letter", "level", "lever", "liberty", "lichen", "lilac",
        "lily", "lime", "linen", "lion", "lizard", "llama", "lobby", "lobster",
        "locket", "lodge", "log", "lotus", "lounge", "lumber", "lunar", "lunch",
        "lyric", "magnet", "maize", "mango", "maple", "marble", "march", "marsh",
        "mason", "meadow", "melon", "mentor", "meteor", "meter", "middle", "milk",
        "mill", "mimic", "mint", "mirror", "mist", "moat", "model", "moment",
    )

    /**
     * Guaranteed entropy of [recommend] in bits, computed FROM the actual
     * wordlist so it can never drift from it: floor(7·9 + 6.64) = 69.
     */
    val GUARANTEED_BITS: Int = (
        WORDS_PER_PASSPHRASE * ln(WORDS.size.toDouble()) / ln(2.0) +
            ln(DIGIT_CHOICES.toDouble()) / ln(2.0)
        ).toInt()

    /**
     * Size of the wordlist — public only so the unit test can pin it at
     * exactly 512 (9 bits of choice per word is what the math above assumes).
     */
    val WORD_COUNT: Int = WORDS.size

    /**
     * Live strength class of a typed passphrase. Display only — enforcement
     * stays with [KeyPolicy.passphraseProblem]; this never rejects anything.
     */
    fun strength(passphrase: CharSequence): Strength {
        if (passphrase.isEmpty()) return Strength.WEAK
        if (passphrase.length < KeyPolicy.MIN_PASSPHRASE_CHARS) return Strength.WEAK
        val bits = estimateBits(passphrase)
        return when {
            bits < 40 -> Strength.WEAK
            bits < 60 -> Strength.FAIR
            else -> Strength.STRONG
        }
    }

    /**
     * Heuristic entropy estimate in bits: observed character pool × length,
     * minus 10 bits per weakness found (single character class, a run of 3+
     * identical characters, a run of 4+ sequential characters). Floored at 0.
     */
    fun estimateBits(passphrase: CharSequence): Int {
        val n = passphrase.length
        if (n == 0) return 0
        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSymbol = false
        for (i in 0 until n) {
            val c = passphrase[i]
            when {
                c.isLowerCase() -> hasLower = true
                c.isUpperCase() -> hasUpper = true
                c.isDigit() -> hasDigit = true
                else -> hasSymbol = true
            }
        }
        var pool = 0
        if (hasLower) pool += 26
        if (hasUpper) pool += 26
        if (hasDigit) pool += 10
        if (hasSymbol) pool += 33
        if (pool == 0) return 0

        var bits = (n * ln(pool.toDouble()) / ln(2.0)).toInt()

        var penalty = 0
        val classes = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        if (classes == 1) penalty += 10
        if (hasIdenticalRun(passphrase)) penalty += 10
        if (hasSequentialRun(passphrase)) penalty += 10
        return (bits - penalty).coerceAtLeast(0)
    }

    /**
     * A recommended shared passphrase: `word-word-word-word-word-word-word-dd`.
     * Both digits are ASCII (Locale.ROOT) so devices with different digit
     * scripts derive the exact same passphrase.
     */
    fun recommend(random: SecureRandom = SecureRandom()): String {
        val parts = ArrayList<String>(WORDS_PER_PASSPHRASE + 1)
        repeat(WORDS_PER_PASSPHRASE) { parts += WORDS[random.nextInt(WORDS.size)] }
        parts += String.format(Locale.ROOT, "%02d", random.nextInt(DIGIT_CHOICES))
        return parts.joinToString("-")
    }

    /** Any 3+ identical consecutive characters ("aaa", "1111"). */
    private fun hasIdenticalRun(s: CharSequence): Boolean {
        var run = 1
        for (i in 1 until s.length) {
            run = if (s[i] == s[i - 1]) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }

    /** Any 4+ ascending or descending consecutive code points ("abcd", "4321"). */
    private fun hasSequentialRun(s: CharSequence): Boolean {
        if (s.length < 4) return false
        var asc = 1
        var desc = 1
        for (i in 1 until s.length) {
            val delta = s[i].code - s[i - 1].code
            asc = if (delta == 1) asc + 1 else 1
            desc = if (delta == -1) desc + 1 else 1
            if (asc >= 4 || desc >= 4) return true
        }
        return false
    }

}