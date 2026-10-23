package com.easy.easyai.autoconfigure.r2dbc

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class R2dbcUrlsTest {

    @Nested
    inner class `connect timeout rewrite` {

        @Test
        fun `appends a bounded connect to a PostgreSQL url`() {
            assertEquals(
                "r2dbc:postgresql://localhost:5432/easyai?connectTimeout=5",
                R2dbcUrls.withConnectTimeout("r2dbc:postgresql://localhost:5432/easyai", 5)
            )
        }

        @Test
        fun `joins an existing query string`() {
            assertEquals(
                "r2dbc:postgresql://db:5432/x?ssl=true&connectTimeout=3",
                R2dbcUrls.withConnectTimeout("r2dbc:postgresql://db:5432/x?ssl=true", 3)
            )
        }

        @Test
        fun `keeps a configured timeout`() {
            val url = "r2dbc:postgresql://db:5432/x?connectTimeout=30"
            assertEquals(url, R2dbcUrls.withConnectTimeout(url, 5))
        }

        @Test
        fun `covers the pooled postgres form`() {
            assertEquals(
                "r2dbc:pool:postgresql://db:5432/x?connectTimeout=5",
                R2dbcUrls.withConnectTimeout("r2dbc:pool:postgresql://db:5432/x", 5)
            )
        }

        @Test
        fun `leaves non-PostgreSQL urls alone`() {
            val url = "r2dbc:h2:mem:///easyai;MODE=MYSQL"
            assertEquals(url, R2dbcUrls.withConnectTimeout(url, 5))
        }

        @Test
        fun `a non-positive timeout disables the rewrite`() {
            val url = "r2dbc:postgresql://localhost:5432/easyai"
            assertEquals(url, R2dbcUrls.withConnectTimeout(url, 0))
        }
    }
}
