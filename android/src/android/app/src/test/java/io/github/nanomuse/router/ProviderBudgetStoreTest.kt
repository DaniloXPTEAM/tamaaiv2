package io.github.nanomuse.router

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderBudgetStoreTest {
    @Test fun `blank is unknown and only positive bounded decimal integers are accepted`() {
        assertEquals(ProviderBudgetStore.Input.Valid(null), ProviderBudgetStore.parse("  "))
        assertEquals(ProviderBudgetStore.Input.Valid(8000), ProviderBudgetStore.parse(" 8000 "))
        for (value in listOf("0", "-1", "+1", "8,000", "1.5", "2147483648", "a")) {
            assertEquals(value, ProviderBudgetStore.Input.Invalid, ProviderBudgetStore.parse(value))
        }
        assertEquals(ProviderBudgetStore.Input.Valid(Int.MAX_VALUE), ProviderBudgetStore.parse("2147483647"))
    }
}
