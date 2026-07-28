package eu.darken.butler.common.upgrade.core

import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class UpgradeRepoFossTest : BaseTest() {

    @BeforeEach
    fun setup() {

    }

    @AfterEach
    fun teardown() {

    }

    @Test fun `test upgrade info pro status mapping`() {
        UpgradeRepoFoss.Info(
            isPro = false,
            upgradedAt = null,
        ).apply {
            type shouldBe UpgradeRepo.Type.FOSS
            isPro shouldBe false
        }

        UpgradeRepoFoss.Info(
            isPro = true,
            upgradedAt = Instant.fromEpochMilliseconds(0),
        ).isPro shouldBe true
    }
}