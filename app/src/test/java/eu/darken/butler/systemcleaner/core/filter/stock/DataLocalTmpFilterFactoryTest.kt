package eu.darken.butler.systemcleaner.core.filter.stock

import eu.darken.butler.common.areas.DataArea
import eu.darken.butler.common.rngString
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.systemcleaner.core.SystemCleanerSettings
import eu.darken.butler.systemcleaner.core.filter.SystemCleanerFilterTest
import eu.darken.butler.systemcleaner.core.sieve.SystemCrawlerSieve
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.mockDataStoreValue

class DataLocalTmpFilterFactoryTest : SystemCleanerFilterTest() {

    @BeforeEach
    override fun setup() {
        super.setup()
    }

    @AfterEach
    override fun teardown() {
        super.teardown()
    }

    private fun create() = DataLocalTmpFilter(
        sieveFactory = object : SystemCrawlerSieve.Factory {
            override fun create(config: SystemCrawlerSieve.Config): SystemCrawlerSieve =
                SystemCrawlerSieve(config, fileForensics)
        },
        gatewaySwitch = gatewaySwitch,
    )

    @Test fun testFilter() = runTest {
        mockDefaults()
        neg(DataArea.Type.DATA, "local", Flag.Dir)
        neg(DataArea.Type.DATA, "local/tmp", Flag.Dir)
        pos(DataArea.Type.DATA, "local/tmp/$rngString", Flag.Dir)
        pos(DataArea.Type.DATA, "local/tmp/$rngString", Flag.File)
        confirm(create())
    }

    @Test fun `only with root`() = runTest {
        DataLocalTmpFilter.Factory(
            settings = mockk<SystemCleanerSettings>().apply {
                coEvery { filterLocalTmpEnabled } returns mockDataStoreValue(true)
            },
            filterProvider = mockk(),
            rootManager = mockk<RootManager>().apply {
                every { useRoot } returns flowOf(true)
            }
        ).isEnabled() shouldBe true

        DataLocalTmpFilter.Factory(
            settings = mockk<SystemCleanerSettings>().apply {
                coEvery { filterLocalTmpEnabled } returns mockDataStoreValue(true)
            },
            filterProvider = mockk(),
            rootManager = mockk<RootManager>().apply {
                every { useRoot } returns flowOf(false)
            }
        ).isEnabled() shouldBe false
    }
}