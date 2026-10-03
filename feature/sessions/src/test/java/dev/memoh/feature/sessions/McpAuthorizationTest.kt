package dev.memoh.feature.sessions

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class McpAuthorizationTest {
    @Test fun `callback codes are decoded and only the pending OAuth state can be exchanged`() {
        val body = mcpCallbackBody("https://app.memoh.net/callback?code=a%2Bb&state=pending%2Fstate", "pending/state")
        assertEquals("a+b", body["code"]!!.jsonPrimitive.content)
        assertEquals("pending/state", body["state"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { mcpCallbackBody("https://app.memoh.net/callback?code=a&state=other", "pending") }
        assertThrows(IllegalArgumentException::class.java) { mcpCallbackBody("https://app.memoh.net/callback?code=a&state=pending", null) }
    }
    @Test fun `cancelled empty and ambiguous callbacks cannot be sent to the token exchange`() {
        for (query in listOf("error=access_denied&state=pending", "code=&state=pending", "code=a&state=", "code=a&state=pending&state=other", "code=a&code=b&state=pending")) {
            assertThrows(RuntimeException::class.java) { mcpCallbackBody("https://app.memoh.net/callback?$query", "pending") }
        }
    }
}
