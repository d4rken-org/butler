package eu.darken.butler.apps.core.details

import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class AppDetailsArgumentsSerializationTest : BaseTest() {

    private val json = SerializationCommonModule().json()

    private val installId = InstallId(Pkg.Id("com.example.app"), UserHandle2(0))

    @Test
    fun `serialize with defaults`() {
        val args = AppDetailsArguments(installId = installId)
        val serialized = json.encodeToJsonElement<AppDetailsArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "installId": {
                    "pkgId": {"name": "com.example.app"},
                    "userHandle": {"handleId": 0}
                },
                "initialTab": "OVERVIEW"
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize without optional fields`() {
        val jsonString = """
            {
                "installId": {
                    "pkgId": {"name": "com.example.app"},
                    "userHandle": {"handleId": 0}
                }
            }
        """

        val args = json.decodeFromString<AppDetailsArguments>(jsonString)

        args shouldBe AppDetailsArguments(installId = installId)
    }

    @Test
    fun `the user handle survives the roundtrip so work profile tabs stay distinct`() {
        val original = AppDetailsArguments(
            installId = InstallId(Pkg.Id("com.example.app"), UserHandle2(10)),
        )

        val serialized = json.encodeToJsonElement<AppDetailsArguments>(original)

        serialized.toString().toComparableJson() shouldBe """
            {
                "installId": {
                    "pkgId": {"name": "com.example.app"},
                    "userHandle": {"handleId": 10}
                },
                "initialTab": "OVERVIEW"
            }
        """.toComparableJson()

        json.decodeFromString<AppDetailsArguments>(serialized.toString()) shouldBe original
    }

    @Test
    fun `the cached app label roundtrips`() {
        val original = AppDetailsArguments(
            installId = installId,
            appLabel = "Example App",
        )

        val serialized = json.encodeToJsonElement<AppDetailsArguments>(original)
        val deserialized = json.decodeFromString<AppDetailsArguments>(serialized.toString())

        deserialized shouldBe original
        deserialized.appLabel shouldBe "Example App"
    }

    @Test
    fun `an unresolved app label is omitted instead of written as null`() {
        val serialized = json.encodeToJsonElement<AppDetailsArguments>(
            AppDetailsArguments(installId = installId, appLabel = null)
        )

        serialized.toString().contains("appLabel") shouldBe false
    }

    @Test
    fun `callerWorkspaceId is session-transient`() {
        val original = AppDetailsArguments(
            installId = installId,
            callerWorkspaceId = Workspace.Id(),
        )

        val serialized = json.encodeToJsonElement<AppDetailsArguments>(original)

        serialized.toString().toComparableJson() shouldBe """
            {
                "installId": {
                    "pkgId": {"name": "com.example.app"},
                    "userHandle": {"handleId": 0}
                },
                "initialTab": "OVERVIEW"
            }
        """.toComparableJson()

        val deserialized = json.decodeFromString<AppDetailsArguments>(serialized.toString())
        deserialized shouldBe original.copy(callerWorkspaceId = null)
    }

    @Test
    fun `roundtrip with initialTab`() {
        val original = AppDetailsArguments(
            installId = installId,
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
            installId = installId,
            initialTab = DetailTab.PACKAGE_INFO,
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<AppDetailsArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
