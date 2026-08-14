package com.hsucode.app

import com.hsucode.data.IdentityEntity
import com.hsucode.data.MemoryEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureLogicTest {
    @Test fun roleCardRoundTripKeepsCoreFields() {
        val original = IdentityEntity(name = "测试角色", systemPrompt = "负责审查代码", description = "审查", openingStatement = "开始")
        val decoded = RoleCardCodec.decode(RoleCardCodec.encode(original)).getOrThrow()
        assertEquals(original.name, decoded.name); assertEquals(original.systemPrompt, decoded.systemPrompt); assertEquals(original.openingStatement, decoded.openingStatement)
    }

    @Test fun memoryRelationsUseSharedTags() {
        val a = MemoryEntity(id = 1, title = "A", content = "", tags = "android,pty")
        val b = MemoryEntity(id = 2, title = "B", content = "", tags = "pty")
        assertEquals(setOf(2L), MemoryRelations.relatedByTags(listOf(a, b))[1L])
        assertTrue(MemoryRelations.withRelationTags(a, setOf(2)).tags.contains("related:2"))
    }

    @Test fun extensionManifestRejectsInsecureSource() {
        val invalid = ExtensionManifestValidator.parse(JSONObject().put("id", "demo").put("sourceUrl", "http://example.com").toString())
        assertTrue(invalid.isFailure)
    }
}
