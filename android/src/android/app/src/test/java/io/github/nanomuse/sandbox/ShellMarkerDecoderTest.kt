package io.github.nanomuse.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellMarkerDecoderTest {
    private val id = "a1b2c3d4"
    private val marker = "__MINIS_DONE_${id}_EXIT_7__\n"

    private fun decode(chunks: List<String>): Pair<String, Int?> {
        val parser = ShellMarkerDecoder(id)
        val output = StringBuilder()
        var exit: Int? = null
        for (chunk in chunks) {
            val frame = parser.feed(chunk)
            output.append(frame.text)
            if (frame.exitCode != null) {
                assertNull("A marker must complete only once", exit)
                exit = frame.exitCode
            }
        }
        output.append(parser.finish())
        return output.toString() to exit
    }

    @Test fun `marker and status survive every two and three chunk split`() {
        val input = "before á\n" + marker
        for (first in 0..input.length) {
            assertEquals("two-way split at $first", "before á\n" to 7,
                decode(listOf(input.substring(0, first), input.substring(first))))
            for (second in first..input.length) {
                assertEquals("three-way split at $first and $second", "before á\n" to 7,
                    decode(listOf(input.substring(0, first), input.substring(first, second), input.substring(second))))
            }
        }
    }

    @Test fun `ordinary output streams without waiting for a marker`() {
        val parser = ShellMarkerDecoder(id)
        assertEquals("hello", parser.feed("hello").text)
        assertEquals("", parser.feed("_").text)
        assertEquals("_world", parser.feed("world").text)
        assertEquals("", parser.finish())
    }

    @Test fun `mismatched and malformed candidates remain ordinary output`() {
        val invalid = "__MINIS_DONE_${id}_EXIT_x__|__MINIS_DONE_wrong_EXIT_0__|" +
            "__MINIS_DONE_${id}_EXIT_7__not-a-line-end|"
        val tooLong = "__MINIS_DONE_${id}_EXIT_12345678901__|"
        val chunks = (invalid + tooLong + marker).map(Char::toString)
        assertEquals(invalid + tooLong to 7, decode(chunks))
    }

    @Test fun `an unfinished marker is returned as output on process exit`() {
        val partial = "__MINIS_DONE_${id}_EXIT_2_"
        val parser = ShellMarkerDecoder(id)
        val frame = parser.feed("body\n$partial")
        assertEquals("body\n", frame.text)
        assertNull(frame.exitCode)
        assertEquals(partial, parser.finish())
        assertEquals("", parser.finish())
    }

    @Test fun `exit codes are parsed only after the complete marker line`() {
        val parser = ShellMarkerDecoder(id)
        assertNull(parser.feed("__MINIS_DONE_${id}_EXIT_25").exitCode)
        assertNull(parser.feed("5_").exitCode)
        assertNull(parser.feed("_").exitCode)
        assertEquals(255, parser.feed("\nignored after marker").exitCode)
        assertNull(parser.feed(marker).exitCode)
        assertEquals("", parser.finish())
    }

    @Test fun `a split CRLF terminates the marker without entering the output`() {
        val parser = ShellMarkerDecoder(id)
        val first = parser.feed("body__MINIS_DONE_${id}_EXIT_0__\r")
        assertEquals("body", first.text)
        assertNull(first.exitCode)
        assertEquals(0, parser.feed("\n").exitCode)
        assertEquals("", parser.finish())
    }

    @Test fun `a large stream with lookalike prefixes is neither duplicated nor lost`() {
        val text = "chunk__MINIS_DONE_${id}_NOT_A_MARKER_".repeat(4_000)
        val parser = ShellMarkerDecoder(id)
        val output = StringBuilder()
        for (part in text.chunked(127)) output.append(parser.feed(part).text)
        output.append(parser.finish())
        assertEquals(text, output.toString())
        assertTrue(output.length > 100_000)
    }
}
