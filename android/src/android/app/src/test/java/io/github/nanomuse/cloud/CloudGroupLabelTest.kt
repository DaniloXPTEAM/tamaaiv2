package io.github.nanomuse.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebrand renamed the Cloud group, not the group.
 *
 * A person who signed in before the rename already has a group called "nanoMuse Cloud" saved in
 * their database. The sign-in path decides whether to add the recommended model to an existing
 * group or create one, and it recognises ours by name - so matching only the new string would
 * hand that person a *second*, duplicate Cloud group on their next sign-in. That is silent data
 * damage, not a cosmetic mismatch, which is why the legacy name is kept and pinned here.
 */
class CloudGroupLabelTest {

    @Test
    fun `the new name is ours`() {
        assertTrue(NanoMuseCloud.ownsGroup(NanoMuseCloud.LABEL))
        assertTrue(NanoMuseCloud.ownsGroup("TAMA AI Cloud"))
    }

    @Test
    fun `the pre-rebrand name is still ours, so an existing group is reused`() {
        assertTrue(NanoMuseCloud.ownsGroup("nanoMuse Cloud"))
        assertTrue("nanoMuse Cloud" in NanoMuseCloud.LEGACY_LABELS)
    }

    @Test
    fun `somebody else's group is not ours`() {
        assertFalse(NanoMuseCloud.ownsGroup("Work"))
        assertFalse(NanoMuseCloud.ownsGroup("OpenAI"))
        assertFalse(NanoMuseCloud.ownsGroup(""))
        // near-misses must not match: a prefix rule would swallow a person's own group
        assertFalse(NanoMuseCloud.ownsGroup("TAMA AI Cloud (mine)"))
        assertFalse(NanoMuseCloud.ownsGroup("nanoMuse"))
        assertFalse(NanoMuseCloud.ownsGroup("nanoMuse Cloud 2"))
    }

    @Test
    fun `the label is display text and the legacy list is not empty`() {
        assertTrue(NanoMuseCloud.LEGACY_LABELS.isNotEmpty())
        // The label is shown in the picker; it must not be blank.
        assertTrue(NanoMuseCloud.LABEL.isNotBlank())
    }
}
