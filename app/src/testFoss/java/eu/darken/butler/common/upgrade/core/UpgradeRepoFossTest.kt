package eu.darken.butler.common.upgrade.core

import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.time.Instant

class UpgradeRepoFossTest : BaseTest() {

    @BeforeEach
    fun setup() {

    }

    @AfterEach
    fun teardown() {

    }

    @Test fun `test upgrade info pro status mapping`() {
        UpgradeRepoFoss.Info(
            isUpgraded = false,
            upgradedAt = null,
        ).apply {
            type shouldBe UpgradeRepo.Type.FOSS
            isUpgraded shouldBe false
        }

        UpgradeRepoFoss.Info(
            isUpgraded = true,
            upgradedAt = Instant.EPOCH,
        ).isUpgraded shouldBe true
    }
}