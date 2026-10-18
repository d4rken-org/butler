package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

class DocsUrlsDataTest {

    private val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "docs/_data/languages.json").isFile }

    @Test
    fun `app languages match documentation languages`() {
        val languages = Json.parseToJsonElement(File(repo, "docs/_data/languages.json").readText()).jsonArray
        languages.associate {
            it.jsonObject.getValue("code").jsonPrimitive.content to it.jsonObject.getValue("path").jsonPrimitive.content
        } shouldBe DocsUrls.LANGUAGE_PATHS
    }

    @Test
    fun `English only routes match the documentation checks`() {
        val pages = Json.parseToJsonElement(File(repo, "tooling/docs/english_only.json").readText()).jsonArray
        pages.map { it.jsonPrimitive.content }.toSet() shouldBe DocsUrls.ENGLISH_ONLY
    }
}
