package eu.darken.butler.common.files.validation

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.SmbLocationInput
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.uuid.Uuid

class FilenameValidatorTest : BaseTest() {

    private val validator = FilenameValidator()

    // Storage Context Detection Tests

    @Test
    fun `public storage detection for storage emulated path`() {
        val path = LocalPath.build(File("/storage/emulated/0/Documents"))
        val result = validator.validate("<test>", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.context shouldBe FilenameValidator.StorageContext.PUBLIC
    }

    @Test
    fun `public storage detection for sdcard path`() {
        val path = LocalPath.build(File("/sdcard/Pictures"))
        val result = validator.validate("<test>", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.context shouldBe FilenameValidator.StorageContext.PUBLIC
    }

    @Test
    fun `root storage detection for data path`() {
        val path = LocalPath.build(File("/data/data/com.example"))
        val result = validator.validate("<test>", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Valid>()
    }

    @Test
    fun `root storage detection for system path`() {
        val path = LocalPath.build(File("/system/bin"))
        val result = validator.validate("<test>", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Valid>()
    }

    // Public Storage Validation Tests

    @Test
    fun `public storage allows valid filenames`() {
        val path = LocalPath.build(File("/sdcard/test"))

        validator.validate("file.txt", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("folder", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("my-file_2024.jpg", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("file (1).pdf", path) shouldBe FilenameValidator.ValidationResult.Valid
    }

    @Test
    fun `public storage blocks less than char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("<file", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('<')
    }

    @Test
    fun `public storage blocks greater than char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("file>", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('>')
    }

    @Test
    fun `public storage blocks colon char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("file:name", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf(':')
    }

    @Test
    fun `public storage blocks double quote char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("file\"name", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('"')
    }

    @Test
    fun `public storage blocks pipe char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("file|name", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('|')
    }

    @Test
    fun `public storage blocks question mark char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("file?", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('?')
    }

    @Test
    fun `public storage blocks asterisk char`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("*.*", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('*')
    }

    @Test
    fun `public storage reports multiple invalid chars`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("<file>:test", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('<', '>', ':')
    }

    // Root Storage Validation Tests

    @Test
    fun `root storage allows special chars that public storage blocks`() {
        val path = LocalPath.build(File("/data/test"))

        validator.validate("<file>", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("file:name", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("*.*", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("file?", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("file|name", path) shouldBe FilenameValidator.ValidationResult.Valid
    }

    @Test
    fun `root storage blocks forward slash`() {
        val path = LocalPath.build(File("/data/test"))
        val result = validator.validate("file/name", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('/')
    }

    @Test
    fun `root storage blocks null byte`() {
        val path = LocalPath.build(File("/data/test"))
        val result = validator.validate("file\u0000name", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('\u0000')
    }

    // Edge Cases

    @Test
    fun `blank name returns valid`() {
        val path = LocalPath.build(File("/sdcard/test"))

        validator.validate("", path) shouldBe FilenameValidator.ValidationResult.Valid
        validator.validate("   ", path) shouldBe FilenameValidator.ValidationResult.Valid
    }

    @Test
    fun `validation works with all restricted chars in one name`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("<>:\"|?*", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('<', '>', ':', '"', '|', '?', '*')
    }

    @Test
    fun `mixed valid and invalid chars reports only invalid`() {
        val path = LocalPath.build(File("/sdcard/test"))
        val result = validator.validate("valid<invalid>valid", path)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('<', '>')
    }

    // SMB

    private val smbPath = SmbPath.root(Uuid.parse("11111111-2222-3333-4444-555555555555"))

    @Test
    fun `smb context is detected for network paths`() {
        val result = validator.validate("bad:name", smbPath)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.context shouldBe FilenameValidator.StorageContext.SMB
    }

    @Test
    fun `smb rejects the characters windows servers refuse`() {
        val result = validator.validate("a\\b/c:d*e?f\"g<h>i|j", smbPath)

        result.shouldBeInstanceOf<FilenameValidator.ValidationResult.Invalid>()
        result.invalidChars shouldBe setOf('\\', '/', ':', '*', '?', '\"', '<', '>', '|')
    }

    @Test
    fun `smb accepts an ordinary name`() {
        validator.validate("Holiday 2024.mkv", smbPath) shouldBe FilenameValidator.ValidationResult.Valid
    }

    @Test
    fun `smb reports whole-name problems separately from characters`() {
        validator.validate("..", smbPath)
            .shouldBeInstanceOf<FilenameValidator.ValidationResult.InvalidName>()
            .reason shouldBe SmbLocationInput.NameIssue.TRAVERSAL

        validator.validate("trailing.", smbPath)
            .shouldBeInstanceOf<FilenameValidator.ValidationResult.InvalidName>()
            .reason shouldBe SmbLocationInput.NameIssue.TRAILING_DOT_OR_SPACE

        validator.validate("trailing ", smbPath)
            .shouldBeInstanceOf<FilenameValidator.ValidationResult.InvalidName>()
            .reason shouldBe SmbLocationInput.NameIssue.TRAILING_DOT_OR_SPACE
    }
}
