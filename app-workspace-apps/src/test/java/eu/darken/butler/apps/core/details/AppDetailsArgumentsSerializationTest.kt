package eu.darken.butler.apps.core.details

import eu.darken.butler.apps.core.arguments.AppDetailsArguments
import eu.darken.butler.apps.core.arguments.DetailTab
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class AppDetailsArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    @Test
    fun `serialize with defaults`() {
        val args = AppDetailsArguments(packageName = "com.example.app")
        val serialized = json.encodeToJsonElement<AppDetailsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "packageName": "com.example.app",
                "initialTab": "OVERVIEW",
                "type": "APP_DETAILS"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize without optional fields`() {
        val jsonString = """
            {
                "packageName": "com.example.app"
            }
        """

        val args = json.decodeFromString<AppDetailsArguments>(jsonString)

        args shouldBe AppDetailsArguments(packageName = "com.example.app")
    }

    @Test
    fun `callerWorkspaceId is session-transient`() {
        val original = AppDetailsArguments(
            packageName = "com.example.app",
            callerWorkspaceId = Workspace.Id(),
        )

        val serialized = json.encodeToJsonElement<AppDetailsArguments>(original)

        serialized.toString().toComparableJson() shouldBe """
            {
                "packageName": "com.example.app",
                "initialTab": "OVERVIEW",
                "type": "APP_DETAILS"
            }
        """.toComparableJson()

        val deserialized = json.decodeFromString<AppDetailsArguments>(serialized.toString())
        deserialized shouldBe original.copy(callerWorkspaceId = null)
    }

    @Test
    fun `roundtrip with initialTab`() {
        val original = AppDetailsArguments(
            packageName = "com.example.app",
            initialTab = DetailTab.COMPONENTS,
        )

        val serialized = json.encodeToJsonElement<AppDetailsArguments>(original)
        val deserialized = json.decodeFromString<AppDetailsArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : AppDetailsWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: AppDetailsArguments): AppDetailsWorkspace = error("unused")
        }
        val original = AppDetailsArguments(
            packageName = "com.example.app",
            initialTab = DetailTab.PACKAGE_INFO,
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<AppDetailsArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
