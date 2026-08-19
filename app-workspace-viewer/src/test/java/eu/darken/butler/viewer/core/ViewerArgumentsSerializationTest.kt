package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.isPausableAsChild
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
    fun `the caller is not persisted`() {
        val modal = ViewerArguments.Default(filePath = path, callerWorkspaceId = Workspace.Id())

        val serialized = json.encodeToJsonElement<ViewerArguments>(modal)

        // Byte-identical to the tab form: a drill-down is never session-saved, and a restored caller
        // could not be reached anyway.
        serialized shouldBe json.encodeToJsonElement<ViewerArguments>(ViewerArguments.Default(filePath = path))
    }

    @Test
    fun `a persisted drill-down deserializes as a tab`() {
        val modal = ViewerArguments.Default(filePath = path, callerWorkspaceId = Workspace.Id())

        val serialized = json.encodeToJsonElement<ViewerArguments>(modal)
        val deserialized = json.decodeFromString<ViewerArguments>(serialized.toString())

        deserialized shouldBe ViewerArguments.Default(filePath = path)
        (deserialized as Workspace.ArgumentsWithCaller).callerWorkspaceId shouldBe null
    }

    @Test
    fun `a drill-down may be paused together with its owner`() {
        val modal = ViewerArguments.Default(filePath = path, callerWorkspaceId = Workspace.Id())

        modal.isPausableAsChild shouldBe true
    }

    @Test
    fun `the content path is the file path`() {
        val args = ViewerArguments.Default(filePath = path)
        (args as Workspace.ArgumentsWithContentPath).contentPath shouldBe path
    }

    private val streamed = ViewerArguments.Streamed(
        uriString = "content://com.example.files/document/42",
        displayName = "holiday.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2_411_200L,
        arrivalId = "arrival-1",
    )

    /**
     * Streamed arguments are never written to the session, but they still have to SERIALIZE:
     * ExternalImportSweeper serializes every live workspace's arguments to work out what still
     * references a cache import, and it treats one failure as "cannot tell" and skips the whole
     * sweep. A broken serializer here would silently stop every import from ever being reclaimed.
     */
    @Test
    fun `roundtrip Streamed serialization`() {
        val serialized = json.encodeToJsonElement<ViewerArguments>(streamed)

        json.decodeFromString<ViewerArguments>(serialized.toString()) shouldBe streamed
    }

    @Test
    fun `serialize Streamed includes type discriminator`() {
        val serialized = json.encodeToJsonElement<ViewerArguments>(streamed)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "streamed",
                "uriString": "content://com.example.files/document/42",
                "displayName": "holiday.jpg",
                "mimeType": "image/jpeg",
                "sizeBytes": 2411200,
                "arrivalId": "arrival-1"
            }
        """.toComparableJson()
    }

    @Test
    fun `streamed arguments refuse to be persisted`() {
        streamed.isPersistable shouldBe false
        ViewerArguments.Default(filePath = path).isPersistable shouldBe true
    }

    // "Streamed carries no content path" needs no test: it does not implement
    // ArgumentsWithContentPath, so the compiler rejects any attempt to read one. What the viewer
    // actually publishes is asserted on Workspace.Info in ViewerWorkspaceClassificationTest.

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
