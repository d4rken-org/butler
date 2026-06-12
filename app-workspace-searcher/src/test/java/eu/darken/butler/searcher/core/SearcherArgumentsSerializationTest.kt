package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SearcherArgumentsSerializationTest : BaseTest() {

    private val json = Json(SerializationCommonModule().json()) {
        serializersModule = SerializersModule {
            include(SerializationCommonModule().json().serializersModule)
            polymorphic(APath::class) {
                subclass(LocalPath::class)
                subclass(SAFPath::class)
            }
        }
    }

    @Test
    fun `serialize Default includes type discriminator`() {
        val args = SearcherArguments.Default()
        val serialized = json.encodeToJsonElement<SearcherArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "startSearch": false
            }
        """.toComparableJson()
    }

    @Test
    fun `serialize Default with startTargets includes type discriminator`() {
        val args = SearcherArguments.Default(
            startTargets = listOf(
                SearchTarget.Path(
                    path = LocalPath.build("/sdcard/Download"),
                    enabled = true,
                ),
            ),
        )
        val serialized = json.encodeToJsonElement<SearcherArguments>(args)

        serialized.toString().toComparableJson() shouldBe """
            {
                "type": "arguments",
                "startSearch": false,
                "startTargets": [
                    {
                        "type": "path",
                        "path": {
                            "type": "LOCAL",
                            "file": "/sdcard/Download"
                        },
                        "enabled": true
                    }
                ]
            }
        """.toComparableJson()
    }

    @Test
    fun `deserialize Default from JSON with discriminator`() {
        val jsonString = """
            {
                "type": "arguments"
            }
        """

        val args = json.decodeFromString<SearcherArguments>(jsonString)

        args shouldBe SearcherArguments.Default()
    }

    @Test
    fun `roundtrip Default serialization`() {
        val original = SearcherArguments.Default(
            startTargets = listOf(
                SearchTarget.Path(
                    path = LocalPath.build("/data/local/tmp"),
                    enabled = true,
                ),
            ),
        )

        val serialized = json.encodeToJsonElement<SearcherArguments>(original)
        val deserialized = json.decodeFromString<SearcherArguments>(serialized.toString())

        deserialized shouldBe original
    }

    @Test
    fun `factory serialization matches direct serialization and roundtrips`() {
        val factory = object : SearcherWorkspace.Factory {
            override fun create(id: Workspace.Id, arguments: SearcherArguments): SearcherWorkspace = error("unused")
        }
        val original = SearcherArguments.Default(
            startTargets = listOf(
                SearchTarget.Path(
                    path = LocalPath.build("/sdcard/Download"),
                    enabled = true,
                ),
            ),
        )

        val serialized = factory.serialize(json, original)

        serialized shouldBe json.encodeToJsonElement<SearcherArguments>(original)
        factory.deserialize(json, serialized) shouldBe original
    }
}
