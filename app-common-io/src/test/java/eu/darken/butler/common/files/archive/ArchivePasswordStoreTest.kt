package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ArchivePasswordStoreTest : BaseTest() {

    private val container = LocalPath.build("/storage/emulated/0/Download/secret.7z")

    @Test
    fun `every change moves the generation on`() {
        val store = ArchivePasswordStore()
        val start = store.generation(container)

        store.set(container, "a".toCharArray())
        store.generation(container) shouldBe start + 1
        store.evict(container)
        store.generation(container) shouldBe start + 2
    }

    @Test
    fun `a snapshot pairs the password with its generation`() {
        val store = ArchivePasswordStore()
        store.set(container, "secret".toCharArray())

        val snapshot = store.snapshot(container)

        snapshot.password!!.concatToString() shouldBe "secret"
        snapshot.generation shouldBe store.generation(container)
    }

    @Test
    fun `a conditional eviction leaves a newer password alone`() {
        val store = ArchivePasswordStore()
        store.set(container, "old".toCharArray())
        val checked = store.generation(container)
        store.set(container, "new".toCharArray())

        store.evictIfGeneration(container, checked) shouldBe false
        store.get(container)!!.concatToString() shouldBe "new"

        store.evictIfGeneration(container, store.generation(container)) shouldBe true
        store.get(container) shouldBe null
        store.generation(container) shouldNotBe checked
    }
}
