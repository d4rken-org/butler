package eu.darken.butler.common.files.errors

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

/**
 * A failed staged overwrite names where the user's data survived. These check that the name reaches
 * the rendered, user-visible description - a plain WriteException drops its own message whenever a
 * path is attached.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class WriteExceptionRecoveryMessageTest : BaseTest() {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val destination = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val stagingName = ".ab12cd34.part.photo.jpg"

    private fun originalRestored() = DataKeptException(
        message = "Could not replace photo.jpg, the original was restored and the new data kept as $stagingName",
        path = destination,
        recovery = DataKeptException.Recovery.OriginalInPlace(newData = stagingName),
        cause = IOException("Failed to rename document"),
    )

    @Test
    fun `staged replace failure - rendered description names the recovery file`() {
        val text = originalRestored()
            .getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
    }

    @Test
    fun `staged replace failure through TransferErrorHandler - rendered description names the recovery file`() {
        // TransferErrorHandler.handleError re-wraps the operation's exception like this before it
        // becomes the PathActionIssue the user is shown.
        val wrapped = WriteException(
            path = destination,
            cause = originalRestored(),
        )

        val text = wrapped.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
    }

    @Test
    fun `both-kept failure names the original and the replacement`() {
        val backupName = ".ef56ab78.backup.photo.jpg"
        val text = DataKeptException(
            message = "Could not replace photo.jpg, the original is kept as $backupName, the new data as $stagingName",
            path = destination,
            recovery = DataKeptException.Recovery.BothKept(original = backupName, newData = stagingName),
            cause = IOException("Failed to rename document"),
        ).getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
        text shouldContain backupName
    }

    @Test
    fun `an unfinished swap-in names the staging file and the backup as candidates`() {
        val backupName = ".ef56ab78.backup.photo.jpg"
        val text = DataKeptException(
            message = "Could not replace photo.jpg, the data is in one of photo.jpg, $stagingName, $backupName",
            path = destination,
            recovery = DataKeptException.Recovery.Uncertain(original = backupName, newData = stagingName),
            cause = IOException("Failed to rename document"),
        ).getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
        text shouldContain backupName
    }

    @Test
    fun `an unfinished swap-in without a backup names only the two candidates it has`() {
        val text = DataKeptException(
            message = "Could not replace photo.jpg, the data is in one of photo.jpg, $stagingName",
            path = destination,
            recovery = DataKeptException.Recovery.Uncertain(original = null, newData = stagingName),
            cause = IOException("Failed to rename document"),
        ).getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
        text shouldNotContain ".backup."
    }

    @Test
    fun `a stalled two-step move names the intermediate file and the folder it stayed in`() {
        // A decomposed move renames the file to the staging basename in ITS OWN folder first, so a
        // reparent that throws leaves it there, not among the destination folder's candidates.
        val source = LocalPath.build("/storage/emulated/0/Download/photo.jpg")
        val stagedDestination = LocalPath.build("/storage/emulated/0/DCIM/$stagingName")

        val text = DataKeptException(
            message = "Could not move $stagingName to $stagedDestination",
            path = source,
            recovery = DataKeptException.Recovery.Intermediate(
                original = "photo.jpg",
                intermediate = stagingName,
                destination = stagedDestination,
            ),
            cause = IOException("Failed to move document"),
        ).getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain stagingName
        text shouldContain "photo.jpg"
        text shouldContain stagedDestination.path
    }
}
