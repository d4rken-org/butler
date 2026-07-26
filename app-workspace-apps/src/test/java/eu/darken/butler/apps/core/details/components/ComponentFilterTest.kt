package eu.darken.butler.apps.core.details.components

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ComponentFilterTest : BaseTest() {

    private val mainActivity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = true,
    )
    private val settingsActivity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.SettingsActivity",
        isExported = false,
    )
    private val syncService = ComponentEntry(
        kind = ComponentKind.SERVICE,
        packageName = "com.example.app",
        className = "com.example.app.sync.SyncService",
        isExported = false,
    )
    private val data = ComponentsData(
        activities = listOf(mainActivity, settingsActivity),
        services = listOf(syncService),
    )

    @Test
    fun `a blank query returns the input untouched`() {
        data.filter("") shouldBeSameInstanceAs data
        data.filter("   ") shouldBeSameInstanceAs data
    }

    @Test
    fun `matching the simple name is case-insensitive`() {
        val filtered = data.filter("mainactivity")

        filtered.activities shouldBe listOf(mainActivity)
        filtered.total shouldBe 1
    }

    @Test
    fun `the package portion of the qualified name matches too`() {
        val filtered = data.filter("app.sync")

        filtered.services shouldBe listOf(syncService)
        filtered.activities shouldBe emptyList()
        filtered.total shouldBe 1
    }

    @Test
    fun `unmatched groups drop out entirely`() {
        val filtered = data.filter("Activity")

        filtered.activities shouldBe listOf(mainActivity, settingsActivity)
        filtered.services shouldBe emptyList()
        filtered.total shouldBe 2
    }

    @Test
    fun `a query nothing matches empties the data`() {
        data.filter("nothing-here").total shouldBe 0
    }
}
