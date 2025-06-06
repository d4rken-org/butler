package eu.darken.butler.systemcleaner.core.filter.stock

import eu.darken.butler.common.areas.DataArea.Type.SDCARD
import eu.darken.butler.common.rngString
import eu.darken.butler.systemcleaner.core.filter.SystemCleanerFilterTest
import eu.darken.butler.systemcleaner.core.sieve.SystemCrawlerSieve
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LostDirFilterFactoryTest : SystemCleanerFilterTest() {

    @BeforeEach
    override fun setup() {
        super.setup()
    }

    @AfterEach
    override fun teardown() {
        super.teardown()
    }

    private fun create() = LostDirFilter(
        sieveFactory = object : SystemCrawlerSieve.Factory {
            override fun create(config: SystemCrawlerSieve.Config): SystemCrawlerSieve =
                SystemCrawlerSieve(config, fileForensics)
        },
        gatewaySwitch = gatewaySwitch,
    )

    @Test fun testFilter() = runTest {
        mockDefaults()
        neg(SDCARD, "LOST.DIR", Flag.Dir)
        neg(SDCARD, "somedir", Flag.Dir)
        neg(SDCARD, "somedir/LOST.DIR", Flag.Dir)
        neg(SDCARD, "LOST.DIR/$rngString", Flag.Dir)
        pos(SDCARD, "LOST.DIR/$rngString", Flag.File)
        confirm(create())
    }
}