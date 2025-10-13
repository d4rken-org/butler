package eu.darken.butler.common.files

import eu.darken.butler.common.files.core.local.tryMkFile
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.json.toComparableJson
import java.io.File
import kotlin.time.Instant

class LocalPathTest : BaseTest() {
    private val testFile = File("./testfile")
    private val testFile2 = File("./testfile2")

    private val json = SerializationIOModule().json()

    @AfterEach
    fun cleanup() {
        testFile.delete()
    }

    @Test
    fun `direct serialization with transient fields`() {
        testFile.tryMkFile()
        val original = LocalPath.build(file = testFile)

        // segmentsCache needs to be ignored during serialization
        println(original.segments.toString())

        val jsonString = json.encodeToString(original)
        jsonString.toComparableJson() shouldBe """
            {
                "file": "${testFile.path}"
            }
        """.toComparableJson()

        json.decodeFromString<LocalPath>(jsonString) shouldBe original
    }

    @Test
    fun `deserialization needs to respect transient fields`() {
        testFile.tryMkFile()
        val original = LocalPath.build(file = testFile)

        val jsonString = """
            {
                "file": "${testFile.path}",
                "type":"LOCAL",
                "segmentsCache": [
                    ".",
                    "testfile"
                ]
            }
        """.toComparableJson()

        json.decodeFromString<APath<LocalPath>>(jsonString) shouldBe original
    }

    @Test
    fun `test polymorph serialization`() {
        testFile.tryMkFile()
        val original = LocalPath.build(file = testFile)

        val jsonString = json.encodeToString<APath<LocalPath>>(original)
        jsonString.toComparableJson() shouldBe """
            {
                "file":"${testFile.path}",
                "type":"LOCAL"
            }
        """.toComparableJson()

        json.decodeFromString<APath<LocalPath>>(jsonString) shouldBe original
    }

    @Test
    fun `test polymorph list serialization`() {
        testFile.tryMkFile()
        val original = listOf(
            LocalPath.build(file = testFile),
            LocalPath.build(file = testFile2),
        )

        val jsonString = json.encodeToString(ListSerializer(serializer<APath<LocalPath>>()), original)

        jsonString.toComparableJson() shouldBe """
                [
                    {
                        "file":"${testFile.path}",
                        "type":"LOCAL"
                    }, {
                        "file":"${testFile2.path}",
                        "type":"LOCAL"
                    }
                ]
        """.toComparableJson()

        json.decodeFromString(ListSerializer(serializer<APath<LocalPath>>()), jsonString) shouldBe original
    }

    @Test
    fun `force typing`() {
        val original = RawPath.build("test", "file")

        shouldThrow<SerializationException> {
            val jsonString = json.encodeToString(RawPath.serializer(), original)
            json.decodeFromString(LocalPath.serializer(), jsonString)
        }
    }

    @Test
    fun `path are always absolute`() {
        LocalPath.build("test", "file1").path shouldBe "/test/file1"
        LocalPath.build("").path shouldBe "/"
    }

    @Test
    fun `segment generation`() {
        LocalPath.build("a", "b", "c").segments shouldBe listOf("", "a", "b", "c")
        LocalPath.build().segments shouldBe listOf("")
    }

    @Test
    fun `parent generation`() {
        LocalPath.build("a", "b", "c").parent shouldBe LocalPath.build("a", "b")
        LocalPath.build("a").parent shouldBe LocalPath.build()
        LocalPath.build().parent shouldBe null
    }

    @Test
    fun `path comparison`() {
        val file1a = LocalPath.build("test", "file1")
        val file1b = LocalPath.build("test", "file1")
        val file2 = LocalPath.build("test", "file2")
        file1a shouldBe file1b
        file1a shouldNotBe file2
    }

    @Test
    fun `lookup comparison`() {
        val lookup1a = LocalPathLookup(
            lookedUp = LocalPath.build("test", "file1"),
            fileType = FileType.FILE,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
            target = null,
        )
        val lookup1b = LocalPathLookup(
            lookedUp = LocalPath.build("test", "file1"),
            fileType = FileType.FILE,
            size = 8,
            modifiedAt = Instant.fromEpochMilliseconds(123),
            target = null,
        )
        val lookup1c = LocalPathLookup(
            LocalPath.build("test", "file1"),
            fileType = FileType.DIRECTORY,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
            target = null,
        )
        val lookup2 = LocalPathLookup(
            lookedUp = LocalPath.build("test", "file2"),
            fileType = FileType.FILE,
            size = 16,
            modifiedAt = Instant.fromEpochMilliseconds(0),
            target = null,
        )
        lookup1a shouldNotBe lookup1b
        lookup1a shouldNotBe lookup1c
        lookup1a shouldNotBe lookup2
    }
}