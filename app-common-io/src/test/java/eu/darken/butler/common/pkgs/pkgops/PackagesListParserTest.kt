package eu.darken.butler.common.pkgs.pkgops

import eu.darken.butler.common.files.metadata.AndroidSystemIds
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PackagesListParserTest : BaseTest() {

    @Test
    fun `parse() with real packages list file returns all app UIDs`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages.list"))

        // When
        val result = parser.parse()

        // Then
        result.size shouldBeGreaterThan 300  // Real file has ~300+ app packages
        result.keys.forEach { uid ->
            uid shouldBeGreaterThan AndroidSystemIds.AID_APP_START - 1
        }
    }

    @Test
    fun `parse() returns correct UID to package mappings`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages.list"))

        // When
        val result = parser.parse()

        // Then - verify some known packages from the real file
        result[10318] shouldBe "eu.darken.myperm"
        result[10371] shouldBe "eu.darken.butler"
    }

    @Test
    fun `parse() filters out system UIDs below AID_APP_START`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - system UIDs should be filtered
        result shouldNotContainKey 1000  // system
        result shouldNotContainKey 1001  // radio
        result shouldNotContainKey 1002  // bluetooth
        result shouldNotContainKey 2000  // shell
        result shouldNotContainKey 1073  // network_stack
        result shouldNotContainKey 9999  // boundary below AID_APP_START
    }

    @Test
    fun `parse() includes app UIDs at and above AID_APP_START`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - app UIDs should be included
        result shouldContainKey 10000  // boundary at AID_APP_START
        result shouldContainKey 10123
        result shouldContainKey 15000

        result[10000] shouldBe "com.example.app1"
        result[10123] shouldBe "com.example.app2"
        result[15000] shouldBe "com.test.package"
    }

    @Test
    fun `parse() with non-existent file returns empty map`() {
        // Given
        val parser = PackagesListParser(filePath = "/tmp/this-file-does-not-exist-${System.currentTimeMillis()}.list")

        // When
        val result = parser.parse()

        // Then
        result shouldBe emptyMap()
    }

    @Test
    fun `parse() handles blank lines`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - should not crash and should parse valid entries
        result shouldContainKey 10000
        result shouldContainKey 10123
    }

    @Test
    fun `parse() handles comment lines`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - comments should be ignored, valid entries parsed
        result shouldContainKey 10000
        result shouldContainKey 10123
    }

    @Test
    fun `parse() handles malformed lines gracefully`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - malformed entries should be skipped, valid entries parsed
        result.values shouldNotContain "com.malformed.nouid"
        result.values shouldNotContain "com.malformed.invaliduid"
        result shouldContainKey 10000
        result shouldContainKey 10123
    }

    @Test
    fun `parseLine() with valid app UID extracts correctly`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.example.app 10123 0 /data/user/0/com.example.app default:targetSdkVersion=34 none 0 100 1 @null"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldNotBe null
        result!!.first shouldBe 10123
        result.second shouldBe "com.example.app"
    }

    @Test
    fun `parseLine() with system UID 1000 is filtered`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.android.systemui 1000 0 /data/user/0/com.android.systemui platform:privapp none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with system UID 1001 is filtered`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.android.phone 1001 0 /data/user/0/com.android.phone platform:privapp none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with system UID 1073 is filtered`() {
        // Given
        val parser = PackagesListParser()
        val line =
            "com.google.android.networkstack.tethering 1073 0 /data/user_de/0/com.google.android.networkstack.tethering network_stack:privapp none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with blank line returns null`() {
        // Given
        val parser = PackagesListParser()
        val line = "   "

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with empty line returns null`() {
        // Given
        val parser = PackagesListParser()
        val line = ""

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with comment line returns null`() {
        // Given
        val parser = PackagesListParser()
        val line = "# This is a comment"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with malformed line (no UID) returns null`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.malformed.nouid"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with non-numeric UID returns null`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.malformed.invaliduid notanumber 0 /data/user/0/com.malformed.invaliduid default none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with UID at boundary 9999 is filtered`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.boundary.below 9999 0 /data/user/0/com.boundary.below default:targetSdkVersion=34 none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldBe null
    }

    @Test
    fun `parseLine() with UID at boundary 10000 is included`() {
        // Given
        val parser = PackagesListParser()
        val line = "com.boundary.at 10000 0 /data/user/0/com.boundary.at default:targetSdkVersion=34 none"

        // When
        val result = parser.parseLine(line)

        // Then
        result shouldNotBe null
        result!!.first shouldBe 10000
        result.second shouldBe "com.boundary.at"
    }

    @Test
    fun `parse() continues parsing after errors`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages-test-cases.list"))

        // When
        val result = parser.parse()

        // Then - valid entries should still be parsed despite errors
        result shouldContainKey 10000
        result shouldContainKey 10123
        result shouldContainKey 15000
        result shouldContainKey 10500  // Minimal entry with only 2 fields
        result.size shouldBe 4  // 4 valid app UIDs in test-cases file
    }

    @Test
    fun `parse() handles entries with varying field counts`() {
        // Given
        val parser = PackagesListParser(filePath = getResourcePath("packages.list"))

        // When
        val result = parser.parse()

        // Then - should handle entries with different numbers of fields
        result shouldNotBe null
        result.size shouldBeGreaterThan 0
        // All entries should have UIDs >= 10000
        result.keys.forEach { uid ->
            uid shouldBeGreaterThan AndroidSystemIds.AID_APP_START - 1
        }
    }

    // Helper methods

    /**
     * Gets the absolute path to a test resource file.
     */
    private fun getResourcePath(resourceName: String): String {
        return javaClass.classLoader?.getResource(resourceName)?.path
            ?: throw IllegalArgumentException("Resource not found: $resourceName")
    }
}
