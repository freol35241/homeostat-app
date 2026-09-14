package dev.homeostat.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionTest {
    @Test
    fun `a full fix is the protocol's example`() {
        assertEquals(
            """{"lat":59.33,"lon":18.06,"accuracy":12.0,"battery":87,"fixed_at":1752600000}""",
            Position(59.33, 18.06, 12f, 87, 1752600000).toJson(),
        )
    }

    @Test
    fun `absent fields are omitted, never null`() {
        assertEquals("""{"lat":59.33,"lon":18.06}""", Position(59.33, 18.06).toJson())
        assertEquals("""{"lat":-33.9,"lon":151.2,"battery":5}""", Position(-33.9, 151.2, batteryPercent = 5).toJson())
    }
}
