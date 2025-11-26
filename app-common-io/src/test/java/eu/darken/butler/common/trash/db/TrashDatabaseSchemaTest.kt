package eu.darken.butler.common.trash.db

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class TrashDatabaseSchemaTest : BaseTest() {

    private val schemaDir = "schemas/eu.darken.butler.common.trash.db.TrashDatabase"

    @Test
    fun `schema file exists for current version`() {
        val expectedVersion = 1
        val schemaFile = File("$schemaDir/$expectedVersion.json")
        schemaFile.exists() shouldBe true
    }

    @Test
    fun `schema version matches database annotation`() {
        val schemaFile = File("$schemaDir/1.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val version = schema["database"]?.jsonObject?.get("version")?.jsonPrimitive?.int

        version shouldBe 1
    }

    @Test
    fun `trash_items table has expected columns`() {
        val schemaFile = File("$schemaDir/1.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val entities = schema["database"]?.jsonObject?.get("entities")?.jsonArray
        val table = entities?.firstOrNull {
            it.jsonObject["tableName"]?.jsonPrimitive?.content == "trash_items"
        }?.jsonObject

        table shouldBe table // Table exists

        val fields = table?.get("fields")?.jsonArray
        val columnNames = fields?.map { it.jsonObject["columnName"]?.jsonPrimitive?.content }

        columnNames shouldContainExactlyInAnyOrder listOf(
            "id",
            "originalPath",
            "originalLookup",
            "trashPath",
            "deletedAt",
            "size",
        )
    }

    @Test
    fun `trash_items table has deletedAt index`() {
        val schemaFile = File("$schemaDir/1.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val entities = schema["database"]?.jsonObject?.get("entities")?.jsonArray
        val table = entities?.firstOrNull {
            it.jsonObject["tableName"]?.jsonPrimitive?.content == "trash_items"
        }?.jsonObject

        val indices = table?.get("indices")?.jsonArray
        val indexNames = indices?.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        indexNames shouldContainExactlyInAnyOrder listOf("index_trash_items_deletedAt")
    }
}
