package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class ViewerArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    private val path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = ViewerArguments.Default(filePath = path)
        val serialized = json.encodeToJsonElement<ViewerArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "filePath": {
                    "type": "LOCAL",
                    "file": "/storage/emulated/0/DCIM/photo.jpg"
                }
            }
        """.toComparableJson()
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = ViewerArguments.Default(filePath = path)

        val serialized = json.encodeToJsonElement<ViewerArguments>(original)
        val deserialized = json.decodeFromString<ViewerArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `the content path is the file path`() {
        val args = ViewerArguments.Default(filePath = path)
        (args as Workspace.ArgumentsWithContentPath).contentPath shouldBe path
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : ViewerWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: ViewerArguments): ViewerWorkspace = error("unused")
        }
        val original = ViewerArguments.Default(filePath = path)

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<ViewerArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
