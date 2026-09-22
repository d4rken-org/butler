package eu.darken.butler.common.files.local

import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.uuid.Uuid

/**
 * Provides a fresh [sharedRoot] on emulated storage (FUSE) and a fresh [privateRoot] on the app's internal
 * storage for each test, and deletes both afterwards.
 */
class DeviceStorageRule : TestRule {

    lateinit var sharedRoot: File
        private set
    lateinit var privateRoot: File
        private set

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val created = mutableListOf<File>()
            var failure: Throwable? = null
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                @Suppress("DEPRECATION")
                val sharedParent = File(
                    Environment.getExternalStorageDirectory(),
                    Environment.DIRECTORY_DOWNLOADS,
                )
                grantStorageAccess(context.packageName, sharedParent)
                sharedRoot = createRoot(sharedParent).also { created.add(it) }
                privateRoot = createRoot(context.filesDir).also { created.add(it) }
                base.evaluate()
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                val cleanupError = cleanup(created)
                if (cleanupError != null) {
                    if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }
    }

    private fun grantStorageAccess(packageName: String, sharedParent: File) {
        assumeTrue(
            "All-files access needs API 30+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        )
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        if (!Environment.isExternalStorageManager()) {
            throw AssertionError("$packageName is not an external storage manager, can't use $sharedParent")
        }
    }

    private fun createRoot(parent: File): File {
        val root = File(parent, "butler-devicetest-${Uuid.random()}")
        if (root.exists()) throw AssertionError("Test root already exists: $root")
        if (!root.mkdirs()) throw AssertionError("Failed to create test root: $root")
        return root
    }

    private fun cleanup(roots: List<File>): Throwable? {
        var error: Throwable? = null
        for (root in roots) {
            try {
                deleteTree(root.toPath())
                if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw AssertionError("Test root still exists after cleanup: $root")
                }
            } catch (e: Throwable) {
                if (error == null) error = e else error.addSuppressed(e)
            }
        }
        return error
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
