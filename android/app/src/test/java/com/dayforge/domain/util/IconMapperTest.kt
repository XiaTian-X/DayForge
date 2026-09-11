package com.dayforge.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class IconMapperTest {

    // ── Legacy icons (1-8) ──────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_legacy() {
        assertEquals("water", IconMapper.toIconName(1))
        assertEquals("exercise", IconMapper.toIconName(2))
        assertEquals("sleep", IconMapper.toIconName(3))
        assertEquals("food", IconMapper.toIconName(4))
        assertEquals("book", IconMapper.toIconName(5))
        assertEquals("meditation", IconMapper.toIconName(6))
        assertEquals("work", IconMapper.toIconName(7))
        assertEquals("health", IconMapper.toIconName(8))
    }

    @Test
    fun toIconResId_returnsCorrectResId_legacy() {
        assertEquals(1, IconMapper.toIconResId("water"))
        assertEquals(2, IconMapper.toIconResId("exercise"))
        assertEquals(3, IconMapper.toIconResId("sleep"))
        assertEquals(4, IconMapper.toIconResId("food"))
        assertEquals(5, IconMapper.toIconResId("book"))
        assertEquals(6, IconMapper.toIconResId("meditation"))
        assertEquals(7, IconMapper.toIconResId("work"))
        assertEquals(8, IconMapper.toIconResId("health"))
    }

    // ── Fitness icons (9-17) ────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_fitness() {
        assertEquals("fitness_center", IconMapper.toIconName(9))
        assertEquals("directions_bike", IconMapper.toIconName(10))
        assertEquals("sports_gymnastics", IconMapper.toIconName(11))
        assertEquals("sports", IconMapper.toIconName(12))
        assertEquals("pool", IconMapper.toIconName(13))
        assertEquals("hiking", IconMapper.toIconName(14))
        assertEquals("directions_walk", IconMapper.toIconName(15))
        assertEquals("sports_soccer", IconMapper.toIconName(16))
        assertEquals("sports_basketball", IconMapper.toIconName(17))
    }

    // ── Health icons (18-25) ────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_health() {
        assertEquals("local_hospital", IconMapper.toIconName(18))
        assertEquals("medical_services", IconMapper.toIconName(19))
        assertEquals("healing", IconMapper.toIconName(20))
        assertEquals("bloodtype", IconMapper.toIconName(21))
        assertEquals("sanitizer", IconMapper.toIconName(22))
        assertEquals("restaurant", IconMapper.toIconName(23))
        assertEquals("local_pharmacy", IconMapper.toIconName(24))
        assertEquals("vaccines", IconMapper.toIconName(25))
    }

    // ── Learning icons (26-34) ──────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_learning() {
        assertEquals("school", IconMapper.toIconName(26))
        assertEquals("menu_book", IconMapper.toIconName(27))
        assertEquals("lightbulb", IconMapper.toIconName(28))
        assertEquals("calculate", IconMapper.toIconName(29))
        assertEquals("translate", IconMapper.toIconName(30))
        assertEquals("science", IconMapper.toIconName(31))
        assertEquals("edit_note", IconMapper.toIconName(32))
        assertEquals("psychology", IconMapper.toIconName(33))
        assertEquals("code", IconMapper.toIconName(34))
    }

    // ── Mental icons (35-43) ────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_mental() {
        assertEquals("spa", IconMapper.toIconName(35))
        assertEquals("sentiment_satisfied", IconMapper.toIconName(36))
        assertEquals("mood", IconMapper.toIconName(37))
        assertEquals("psychology_alt", IconMapper.toIconName(38))
        assertEquals("sentiment_very_satisfied", IconMapper.toIconName(39))
        assertEquals("nature", IconMapper.toIconName(40))
        assertEquals("forest", IconMapper.toIconName(41))
        assertEquals("grain", IconMapper.toIconName(42))
        assertEquals("self_improvement", IconMapper.toIconName(43))
    }

    // ── Lifestyle icons (44-52) ─────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_lifestyle() {
        assertEquals("home", IconMapper.toIconName(44))
        assertEquals("shopping_bag", IconMapper.toIconName(45))
        assertEquals("shopping_cart", IconMapper.toIconName(46))
        assertEquals("cleaning_services", IconMapper.toIconName(47))
        assertEquals("local_laundry_service", IconMapper.toIconName(48))
        assertEquals("pets", IconMapper.toIconName(49))
        assertEquals("family_restroom", IconMapper.toIconName(50))
        assertEquals("celebration", IconMapper.toIconName(51))
        assertEquals("nightlife", IconMapper.toIconName(52))
    }

    // ── Task icon (53) ──────────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_task() {
        assertEquals("task_alt", IconMapper.toIconName(53))
    }

    // ── Personal Hygiene icons (54-61) ──────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_personalHygiene() {
        assertEquals("shower", IconMapper.toIconName(54))
        assertEquals("bathtub", IconMapper.toIconName(55))
        assertEquals("wash", IconMapper.toIconName(56))
        assertEquals("brush_teeth", IconMapper.toIconName(57))
        assertEquals("face_wash", IconMapper.toIconName(58))
        assertEquals("hair_brush", IconMapper.toIconName(59))
        assertEquals("shave", IconMapper.toIconName(60))
        assertEquals("nail_care", IconMapper.toIconName(61))
    }

    // ── Social icons (62-66) ────────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_social() {
        assertEquals("people", IconMapper.toIconName(62))
        assertEquals("group", IconMapper.toIconName(63))
        assertEquals("chat", IconMapper.toIconName(64))
        assertEquals("forum", IconMapper.toIconName(65))
        assertEquals("handshake", IconMapper.toIconName(66))
    }

    // ── Finance icons (67-71) ───────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_finance() {
        assertEquals("account_balance", IconMapper.toIconName(67))
        assertEquals("savings", IconMapper.toIconName(68))
        assertEquals("payment", IconMapper.toIconName(69))
        assertEquals("receipt", IconMapper.toIconName(70))
        assertEquals("attach_money", IconMapper.toIconName(71))
    }

    // ── Creative icons (72-76) ──────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_creative() {
        assertEquals("brush", IconMapper.toIconName(72))
        assertEquals("music_note", IconMapper.toIconName(73))
        assertEquals("camera", IconMapper.toIconName(74))
        assertEquals("edit", IconMapper.toIconName(75))
        assertEquals("design_services", IconMapper.toIconName(76))
    }

    // ── Transportation icons (77-79) ────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_transportation() {
        assertEquals("directions_car", IconMapper.toIconName(77))
        assertEquals("train", IconMapper.toIconName(78))
        assertEquals("flight", IconMapper.toIconName(79))
    }

    // ── Entertainment icons (80-82) ─────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_entertainment() {
        assertEquals("movie", IconMapper.toIconName(80))
        assertEquals("tv", IconMapper.toIconName(81))
        assertEquals("sports_esports", IconMapper.toIconName(82))
    }

    // ── Household icons (83-85) ─────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_household() {
        assertEquals("kitchen", IconMapper.toIconName(83))
        assertEquals("build", IconMapper.toIconName(84))
        assertEquals("iron", IconMapper.toIconName(85))
    }

    // ── Outdoor icons (86-88) ───────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_outdoor() {
        assertEquals("park", IconMapper.toIconName(86))
        assertEquals("landscape", IconMapper.toIconName(87))
        assertEquals("terrain", IconMapper.toIconName(88))
    }

    // ── Technology icons (89-91) ────────────────────────────────────────

    @Test
    fun toIconName_returnsCorrectName_technology() {
        assertEquals("phone", IconMapper.toIconName(89))
        assertEquals("computer", IconMapper.toIconName(90))
        assertEquals("devices", IconMapper.toIconName(91))
    }

    // ── Round-trip tests: resId -> name -> resId ────────────────────────

    @Test
    fun roundTrip_allIdsMapBackCorrectly() {
        for (id in 1..91) {
            val name = IconMapper.toIconName(id)
            val backToId = IconMapper.toIconResId(name)
            assertEquals("Round-trip failed for ID $id ($name)", id, backToId)
        }
    }

    @Test
    fun roundTrip_allNamesMapBackCorrectly() {
        val allNames = listOf(
            "water", "exercise", "sleep", "food", "book", "meditation", "work", "health",
            "fitness_center", "directions_bike", "sports_gymnastics", "sports", "pool",
            "hiking", "directions_walk", "sports_soccer", "sports_basketball",
            "local_hospital", "medical_services", "healing", "bloodtype", "sanitizer",
            "restaurant", "local_pharmacy", "vaccines",
            "school", "menu_book", "lightbulb", "calculate", "translate", "science",
            "edit_note", "psychology", "code",
            "spa", "sentiment_satisfied", "mood", "psychology_alt",
            "sentiment_very_satisfied", "nature", "forest", "grain", "self_improvement",
            "home", "shopping_bag", "shopping_cart", "cleaning_services",
            "local_laundry_service", "pets", "family_restroom", "celebration", "nightlife",
            "task_alt",
            "shower", "bathtub", "wash", "brush_teeth", "face_wash", "hair_brush",
            "shave", "nail_care",
            "people", "group", "chat", "forum", "handshake",
            "account_balance", "savings", "payment", "receipt", "attach_money",
            "brush", "music_note", "camera", "edit", "design_services",
            "directions_car", "train", "flight",
            "movie", "tv", "sports_esports",
            "kitchen", "build", "iron",
            "park", "landscape", "terrain",
            "phone", "computer", "devices"
        )
        for (name in allNames) {
            val id = IconMapper.toIconResId(name)
            val backToName = IconMapper.toIconName(id)
            assertEquals("Round-trip failed for name '$name'", name, backToName)
        }
    }

    // ── Fallback / default behavior ─────────────────────────────────────

    @Test
    fun toIconName_returnsDefaultForZero() {
        assertEquals("health", IconMapper.toIconName(0))
    }

    @Test
    fun toIconName_returnsDefaultForNegativeId() {
        assertEquals("health", IconMapper.toIconName(-1))
    }

    @Test
    fun toIconName_returnsDefaultForIdAboveRange() {
        assertEquals("health", IconMapper.toIconName(92))
        assertEquals("health", IconMapper.toIconName(999))
    }

    @Test
    fun toIconResId_returnsDefaultForUnknownName() {
        assertEquals(8, IconMapper.toIconResId("unknown"))
        assertEquals(8, IconMapper.toIconResId(""))
        assertEquals(8, IconMapper.toIconResId("nonexistent_icon"))
    }

    // ── Case insensitivity ──────────────────────────────────────────────

    @Test
    fun toIconResId_isCaseInsensitive() {
        assertEquals(1, IconMapper.toIconResId("WATER"))
        assertEquals(1, IconMapper.toIconResId("Water"))
        assertEquals(1, IconMapper.toIconResId("wAtEr"))
        assertEquals(2, IconMapper.toIconResId("EXERCISE"))
        assertEquals(8, IconMapper.toIconResId("HEALTH"))
        // New icons should also be case-insensitive
        assertEquals(54, IconMapper.toIconResId("SHOWER"))
        assertEquals(67, IconMapper.toIconResId("ACCOUNT_BALANCE"))
        assertEquals(89, IconMapper.toIconResId("PHONE"))
    }

    @Test
    fun isValidIconName_isCaseInsensitive() {
        assertTrue(IconMapper.isValidIconName("WATER"))
        assertTrue(IconMapper.isValidIconName("Water"))
        assertTrue(IconMapper.isValidIconName("SHOWER"))
        assertTrue(IconMapper.isValidIconName("PHONE"))
    }

    // ── Validation: isValidIconResId ────────────────────────────────────

    @Test
    fun isValidIconResId_validatesAllBoundaryIds() {
        // Valid boundaries
        assertTrue(IconMapper.isValidIconResId(1))
        assertTrue(IconMapper.isValidIconResId(91))
        // Invalid boundaries
        assertFalse(IconMapper.isValidIconResId(0))
        assertFalse(IconMapper.isValidIconResId(92))
        assertFalse(IconMapper.isValidIconResId(-1))
        assertFalse(IconMapper.isValidIconResId(999))
    }

    @Test
    fun isValidIconResId_validatesAllValidIds() {
        for (id in 1..91) {
            assertTrue("ID $id should be valid", IconMapper.isValidIconResId(id))
        }
    }

    @Test
    fun isValidIconResId_rejectsIdsJustOutsideRange() {
        assertFalse(IconMapper.isValidIconResId(0))
        assertFalse(IconMapper.isValidIconResId(92))
    }

    // ── Validation: isValidIconName ─────────────────────────────────────

    @Test
    fun isValidIconName_validatesCorrectly() {
        assertTrue(IconMapper.isValidIconName("water"))
        assertTrue(IconMapper.isValidIconName("health"))
        assertTrue(IconMapper.isValidIconName("task_alt"))
        // New category icons
        assertTrue(IconMapper.isValidIconName("shower"))
        assertTrue(IconMapper.isValidIconName("handshake"))
        assertTrue(IconMapper.isValidIconName("account_balance"))
        assertTrue(IconMapper.isValidIconName("devices"))
        // Invalid
        assertFalse(IconMapper.isValidIconName("unknown"))
        assertFalse(IconMapper.isValidIconName(""))
        assertFalse(IconMapper.isValidIconName("nonexistent"))
    }

    // ── Task icon (ID 53) regression ────────────────────────────────────

    @Test
    fun taskIcon_id53_mapsCorrectly() {
        assertEquals("task_alt", IconMapper.toIconName(53))
        assertEquals(53, IconMapper.toIconResId("task_alt"))
        assertTrue(IconMapper.isValidIconResId(53))
        assertTrue(IconMapper.isValidIconName("task_alt"))
    }

    // ── Total mapping count ─────────────────────────────────────────────

    @Test
    fun totalMappingCount_is91() {
        // Verify we have exactly 91 entries by checking all IDs 1-91 resolve
        for (id in 1..91) {
            val name = IconMapper.toIconName(id)
            assertTrue(
                "ID $id mapped to default name instead of specific name",
                name != "health" || id == 8
            )
        }
    }
}
