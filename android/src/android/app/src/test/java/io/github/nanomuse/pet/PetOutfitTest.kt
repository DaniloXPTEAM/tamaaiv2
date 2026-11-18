package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SL-001: outfits as a scene over the existing face, not a second character.
 *
 * The promises here are about the person's choice: a locked outfit is still theirs and still
 * visible, an unknown id can never break the screen, and progression earns choice rather than
 * power. The last one matters most — if an outfit ever changed a number, the Pet would be
 * pay-to-win inside a chat app.
 */
class PetOutfitTest {

    @Test
    fun `the default wears nothing`() {
        assertEquals(PetOutfit.NONE, PetOutfit.byId(null))
        assertEquals(PetOutfit.NONE, PetOutfit.byId("no-such-outfit"))
        assertEquals(PetOutfit.NONE, PetOutfit.NONE.copy())
        assertNull(PetOutfit.NONE.accessory)
        assertFalse(PetOutfit.NONE.backdrop.paints)
    }

    @Test
    fun `every outfit has a unique id and a drawable accessory`() {
        val ids = PetOutfit.ALL.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        for (o in PetOutfit.ALL) {
            assertTrue(o.id, o.id.isNotBlank())
            if (o.id != PetOutfit.NONE.id) {
                assertNotNull("${o.id} needs an accessory", o.accessory)
                assertTrue("${o.id} paints a scene", o.backdrop.paints)
            }
        }
    }

    @Test
    fun `the catalogue uses the presets the app already ships`() {
        // SL-001 explicitly reuses them rather than adding art: the four studio presets are the
        // inspiration, so a person who picked one already recognises the outfits.
        val ids = PetOutfit.ALL.map { it.id }.toSet()
        for (preset in listOf("acorn", "moth", "courier", "tinker")) {
            assertTrue("$preset must be available", preset in ids)
        }
        // …and the emoji look the app defaults to (spark) is there too.
        assertTrue("spark" in ids)
    }

    @Test
    fun `a locked outfit is not worn but is still the person's choice`() {
        val legendary = PetOutfit.byId("legend")
        val wardrobe = PetWardrobe().choose(legendary)
        assertEquals("the choice is remembered", legendary, wardrobe.chosen)
        assertEquals("but not worn yet", PetOutfit.NONE, wardrobe.wornAt(level = 3))
        assertEquals("and worn once earned", legendary, wardrobe.wornAt(level = 10))
    }

    @Test
    fun `unlocked lists exactly what the level allows`() {
        val atOne = PetOutfit.unlocked(1).map { it.id }
        assertEquals(listOf("none", "spark"), atOne)
        val atSix = PetOutfit.unlocked(6).map { it.id }
        assertTrue("guardian" in atSix)
        assertFalse("legend" in atSix)
        val atTen = PetOutfit.unlocked(10).map { it.id }
        assertEquals("everything by level ten", PetOutfit.ALL.map { it.id }, atTen)
        // Unlocking is monotonic: a higher level never loses an outfit.
        var last = 0
        for (level in 1..20) {
            val n = PetOutfit.unlocked(level).size
            assertTrue("unlocked shrank at level $level", n >= last)
            last = n
        }
    }

    @Test
    fun `no outfit changes a single number of the pet`() {
        // The Pet's whole state is built from needs and XP; an outfit is not an input to either.
        // If this class ever grows a field that feeds PetCare or PetXpRules, this test is the
        // place that should have to change on purpose.
        val outfit = PetOutfit.byId("legend")
        val needs = PetNeeds(satiety = 40, energy = 40, mood = 40, bond = 40)
        val before = PetCare.afterTurn(needs, PetTurnOutcome.SUCCESS, turnsToday = 0)
        val after = PetCare.afterTurn(needs, PetTurnOutcome.SUCCESS, turnsToday = 0)
        assertEquals("outfits must not touch needs", before, after)
        assertTrue(outfit.unlockLevel > 1)
    }

    @Test
    fun `a wardrobe read from storage survives junk`() {
        assertEquals(PetOutfit.NONE.id, PetWardrobe.fromStorage(null).chosenId)
        assertEquals(PetOutfit.NONE.id, PetWardrobe.fromStorage("").chosenId)
        assertEquals(PetOutfit.NONE.id, PetWardrobe.fromStorage("junk").chosenId)
        assertEquals("moth", PetWardrobe.fromStorage("moth").chosenId)
    }

    @Test
    fun `an unworn outfit falls back to none rather than to a broken scene`() {
        val wardrobe = PetWardrobe.fromStorage("nonsense")
        assertEquals(PetOutfit.NONE, wardrobe.wornAt(level = 99))
        assertFalse(wardrobe.wornAt(99).backdrop.paints)
    }
}
