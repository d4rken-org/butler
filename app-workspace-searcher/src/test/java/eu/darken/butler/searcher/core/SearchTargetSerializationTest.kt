package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.searcher.core.history.db.SearchQueryConverter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson

class SearchTargetSerializationTest : BaseTest() {

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
    fun `MediaStore target serializes with stable discriminators`() {
        val target: SearchTarget = SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.IMAGES)

        json.encodeToJsonElement(target).toString().toComparableJson() shouldBe """
            {
                "type": "mediastore",
                "collection": "images",
                "enabled": true
            }
        """.toComparableJson()
    }

    @Test
    fun `every collection has a stable serial name`() {
        val expected = mapOf(
            SearchTarget.MediaStore.Collection.IMAGES to "images",
            SearchTarget.MediaStore.Collection.VIDEO to "video",
            SearchTarget.MediaStore.Collection.AUDIO to "audio",
            SearchTarget.MediaStore.Collection.DOWNLOADS to "downloads",
        )
        SearchTarget.MediaStore.Collection.entries.forEach { collection ->
            json.encodeToJsonElement(collection).toString() shouldBe "\"${expected[collection]}\""
        }
    }

    @Test
    fun `every collection round-trips`() {
        SearchTarget.MediaStore.Collection.entries.forEach { collection ->
            val original: SearchTarget = SearchTarget.MediaStore(collection, enabled = false)
            val decoded = json.decodeFromString<SearchTarget>(json.encodeToJsonElement(original).toString())
            decoded shouldBe original
        }
    }

    @Test
    fun `mixed path and mediastore target list round-trips`() {
        val original: List<SearchTarget> = listOf(
            SearchTarget.Path(path = LocalPath.build("/sdcard/Download")),
            SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.AUDIO),
            SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.DOWNLOADS, enabled = false),
        )

        val decoded = json.decodeFromString<List<SearchTarget>>(
            json.encodeToJsonElement(original).toString()
        )

        decoded shouldBe original
    }

    @Test
    fun `history converter round-trips queries with mediastore targets`() {
        val converter = SearchQueryConverter()
        val query = SearchQuery(
            targets = listOf(
                SearchTarget.Path(path = LocalPath.build("/storage/emulated/0")),
                SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.IMAGES),
            ),
        )

        converter.toSearchQuery(converter.fromSearchQuery(query)) shouldBe query
    }
}
