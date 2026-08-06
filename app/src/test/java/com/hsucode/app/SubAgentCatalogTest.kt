package com.hsucode.app

import com.hsucode.data.SubAgentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentCatalogTest {
    @Test
    fun templatesHaveUniqueNamesAndSafeTemperatureRange() {
        val templates = SubAgentCatalog.templates

        assertEquals(templates.size, templates.map { it.name }.distinct().size)
        assertTrue(templates.all { it.temperature in 0f..2f })
        assertTrue(templates.all { it.prompt.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun csvParserTrimsRemovesBlanksAndDeduplicates() {
        assertEquals(
            listOf("file_read", "grep", "web_search"),
            SubAgentCatalog.parseCsv(" file_read,grep,, file_read , web_search ")
        )
    }

    @Test
    fun newAgentDefaultsToEnabledAndControlledTemperature() {
        val agent = SubAgentEntity(name = "验证")

        assertTrue(agent.enabled)
        assertEquals(0.7f, agent.temperature)
        assertFalse(agent.builtin)
    }
}
