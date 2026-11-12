package io.github.riggedlab.hark

import org.junit.Assert.*
import org.junit.Test

class SpeakerMigrationTest {
    @Test fun legacyRosterSplitsWithoutOverwritingNewEdits() {
        val legacy = mapOf(
            "spk_001" to mapOf("name" to "老王", "merged_into" to "spk_002", "first_seen" to "旧时间", "sample" to "旧原句"),
            "spk_002" to mapOf("name" to "老李", "merged_into" to null, "first_seen" to "首次", "sample" to "样本"),
            "spk_003" to mapOf("name" to "", "merged_into" to " "),
        )
        val mac = mapOf("spk_001" to mapOf("first_seen" to "新时间", "sample_start" to 1.5))
        val phone = mapOf("spk_001" to mapOf("name" to "新名字", "merged_into" to null))
        val (mergedMac, mergedPhone) = mergeLegacySpeakers(legacy, mac, phone)
        assertEquals(mapOf("first_seen" to "新时间", "sample_start" to 1.5, "sample" to "旧原句"), mergedMac["spk_001"])
        assertEquals(phone["spk_001"], mergedPhone["spk_001"]) // 用户已拆开，不能重新合并
        assertEquals(mapOf("first_seen" to "首次", "sample" to "样本"), mergedMac["spk_002"])
        assertEquals(mapOf("name" to "老李"), mergedPhone["spk_002"])
        assertTrue(mergedPhone["spk_003"]!!.isEmpty())
        assertEquals(mergedMac to mergedPhone, mergeLegacySpeakers(legacy, mergedMac, mergedPhone))
        val (_, fromEmpty) = mergeLegacySpeakers(legacy, emptyMap(), emptyMap())
        assertEquals("spk_002", fromEmpty["spk_001"]!!["merged_into"])
        assertEquals("老王", fromEmpty["spk_001"]!!["name"])
    }

    @Test fun incomingEmptyMacRosterPreservesMigratedHistory() {
        val legacy = mapOf(
            "spk_001" to mapOf("name" to "老王", "first_seen" to "2026-11-01T10:00:02", "sample" to "历史代表句"),
        )
        val (migratedMac, _) = mergeLegacySpeakers(legacy, emptyMap(), emptyMap())
        // Mac 旧库升级后首次推来空字段名册，不能把手机已迁移的首次时间和样本冲掉
        val incomingFromUpgradedMac = mapOf(
            "spk_001" to mapOf<String, Any?>("first_seen" to null, "sample" to null, "sample_name" to null, "sample_start" to null),
            "spk_002" to mapOf<String, Any?>("first_seen" to "2026-11-12T12:00:01", "sample" to "新说话人原句"),
        )
        val afterSync = mergeMacRoster(incomingFromUpgradedMac, migratedMac)
        assertEquals("2026-11-01T10:00:02", afterSync["spk_001"]!!["first_seen"])
        assertEquals("历史代表句", afterSync["spk_001"]!!["sample"])
        assertEquals("新说话人原句", afterSync["spk_002"]!!["sample"])
    }
}
