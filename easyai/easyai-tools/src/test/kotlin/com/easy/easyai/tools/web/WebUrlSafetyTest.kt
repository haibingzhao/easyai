package com.easy.easyai.tools.web

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The admission rules every outbound fetch path shares; only literal addresses are used, no DNS. */
class WebUrlSafetyTest {

    @Nested
    inner class Scheme {

        @Test
        fun `only http and https with a host are fetchable`() {
            assertTrue(WebUrlSafety.isHttpUrl("https://example.com/skills/futu.zip"))
            assertTrue(WebUrlSafety.isHttpUrl("http://example.com"))
            assertTrue(WebUrlSafety.isHttpUrl("HTTPS://example.com/x"))

            assertFalse(WebUrlSafety.isHttpUrl("ftp://example.com/x"))
            assertFalse(WebUrlSafety.isHttpUrl("file:///etc/passwd"))
            assertFalse(WebUrlSafety.isHttpUrl("javascript:alert(1)"))
            assertFalse(WebUrlSafety.isHttpUrl("https:///no-host"))
            assertFalse(WebUrlSafety.isHttpUrl("not a url"))
        }
    }

    @Nested
    inner class InternalAddresses {

        @Test
        fun `loopback any local link local and site local are refused`() {
            for (url in listOf(
                "http://127.0.0.1/",
                "http://localhost/",
                "http://0.0.0.0/",
                "http://169.254.169.254/latest/meta-data",
                "http://10.1.2.3/",
                "http://192.168.1.10/",
                "http://[::1]/"
            )) {
                val error = assertFailsWith<IllegalArgumentException>(url) { WebUrlSafety.validate(url) }
                assertTrue(error.message!!.contains("internal"), url)
            }
        }

        @Test
        fun `a public literal address passes`() {
            WebUrlSafety.validate("http://8.8.8.8/archive.zip")
        }

        @Test
        fun `a url without a host is refused before resolution`() {
            assertFailsWith<IllegalArgumentException> { WebUrlSafety.validate("file:///etc/passwd") }
        }

        @Test
        fun `an unresolvable host surfaces the resolution failure`() {
            assertFailsWith<UnknownHostException> {
                WebUrlSafety.validate("http://does-not-exist.invalid/archive.zip")
            }
        }
    }

    @Nested
    inner class Redirects {

        private val current = URI("https://www.example.com/skills/futu.zip")

        @Test
        fun `relative protocol relative and absolute targets resolve`() {
            assertEquals(URI("https://www.example.com/skills/raw/futu.zip"), WebUrlSafety.resolveRedirect(current, "raw/futu.zip"))
            assertEquals(URI("https://www.example.com/raw/futu.zip"), WebUrlSafety.resolveRedirect(current, "../raw/futu.zip"))
            assertEquals(URI("https://cdn.example.com/futu.zip"), WebUrlSafety.resolveRedirect(current, "//cdn.example.com/futu.zip"))
            assertEquals(URI("http://other.example.com/futu.zip"), WebUrlSafety.resolveRedirect(current, "http://other.example.com/futu.zip"))
        }

        @Test
        fun `non http and missing locations are not followable`() {
            assertNull(WebUrlSafety.resolveRedirect(current, "file:///etc/passwd"))
            assertNull(WebUrlSafety.resolveRedirect(current, "javascript:alert(1)"))
            assertNull(WebUrlSafety.resolveRedirect(current, null))
            assertNull(WebUrlSafety.resolveRedirect(current, "   "))
        }
    }
}
