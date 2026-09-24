package eu.darken.butler.common.adb.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.IBinder
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.porter.sdk.UserServiceArgs
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Covers [ShizukuWrapper.getManagerPackages] (permission-based manager detection that survives
 * "Hide Shizuku from other apps" mode and forks that rename their package) and how the wrapper reads
 * the SDK's connection: which connection its flows follow, and which answers count as unknown.
 */
class ShizukuWrapperTest {

    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()

    private val porterPermission = "eu.darken.porter.permission.API"
    private val stockPermission = "moe.shizuku.manager.permission.API_V23"
    private val plusPermission = "af.shizuku.plus.permission.API_V23"

    private val dispatcherProvider = object : DispatcherProvider {
        override val IO: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeServer(override val backend: AdbBackend = AdbBackend.PORTER) : AdbServerConnection {
        val permissionFlow = MutableStateFlow<AdbPermissionState>(AdbPermissionState.Denied(permanentlyDenied = false))
        var onCheck: suspend () -> AdbPermissionState = { permissionFlow.value }
        var onRequest: suspend () -> AdbPermissionState = { AdbPermissionState.Granted }

        override val permission: Flow<AdbPermissionState> = permissionFlow
        override suspend fun checkPermission(): AdbPermissionState = onCheck()
        override suspend fun requestPermission(): AdbPermissionState = onRequest()
        override fun userService(args: UserServiceArgs): Flow<IBinder> = emptyFlow()
        override suspend fun stopUserService(args: UserServiceArgs) = Unit
    }

    private class FakeSource : AdbServerSource {
        val server = MutableStateFlow<AdbServerConnection?>(null)
        var onAvailability: suspend () -> AdbAvailability = { AdbAvailability.NotInstalled }

        override val connection: Flow<AdbServerConnection?> = server
        override fun current(): AdbServerConnection? = server.value
        override suspend fun availability(): AdbAvailability = onAvailability()
    }

    private val source = FakeSource()

    private fun wrapper(): ShizukuWrapper {
        every { context.packageManager } returns packageManager
        return ShizukuWrapper(context, dispatcherProvider, source)
    }

    // mockk gives us a real (Objenesis-instantiated) PermissionInfo whose inherited public
    // packageName field we can set directly, without invoking the Android constructor.
    private fun permissionInfo(pkg: String?) = mockk<PermissionInfo>().apply { packageName = pkg }

    private fun definePermission(name: String, owner: String?) {
        every { packageManager.getPermissionInfo(name, any<Int>()) } returns permissionInfo(owner)
    }

    private fun undefinePermission(name: String) {
        every { packageManager.getPermissionInfo(name, any<Int>()) } throws PackageManager.NameNotFoundException()
    }

    private fun undefineAll() {
        undefinePermission(porterPermission)
        undefinePermission(stockPermission)
        undefinePermission(plusPermission)
    }

    @Test
    fun `resolves the declaring package when the Shizuku permission exists`() = runTest {
        undefineAll()
        definePermission(stockPermission, "moe.shizuku.privileged.api")

        wrapper().getManagerPackages() shouldBe listOf("moe.shizuku.privileged.api")
    }

    @Test
    fun `resolves a fork declaring the permission under a different package`() = runTest {
        undefineAll()
        definePermission(stockPermission, "com.example.shizuku.fork")

        wrapper().getManagerPackages() shouldBe listOf("com.example.shizuku.fork")
    }

    @Test
    fun `resolves Porter by its own permission`() = runTest {
        undefineAll()
        definePermission(porterPermission, "eu.darken.porter")

        wrapper().getManagerPackages() shouldBe listOf("eu.darken.porter")
    }

    @Test
    fun `lists Porter first, then stock Shizuku, then Shizuku+`() = runTest {
        definePermission(plusPermission, "af.shizuku.plus.api")
        definePermission(stockPermission, "moe.shizuku.privileged.api")
        definePermission(porterPermission, "eu.darken.porter")

        wrapper().getManagerPackages() shouldBe listOf(
            "eu.darken.porter",
            "moe.shizuku.privileged.api",
            "af.shizuku.plus.api",
        )
    }

    @Test
    fun `is empty when no app declares a manager permission`() = runTest {
        undefineAll()

        wrapper().getManagerPackages() shouldBe emptyList()
    }

    @Test
    fun `is empty on unexpected PackageManager failure`() = runTest {
        every { packageManager.getPermissionInfo(any(), any<Int>()) } throws RuntimeException("OEM quirk")

        wrapper().getManagerPackages() shouldBe emptyList()
    }

    @Test
    fun `skips a declaring package name that is blank`() = runTest {
        every { packageManager.getPermissionInfo(any(), any<Int>()) } returns permissionInfo("")

        wrapper().getManagerPackages() shouldBe emptyList()
    }

    @Test
    fun `falls back to the Shizuku+ permission when the stock permission is undefined`() = runTest {
        undefineAll()
        definePermission(plusPermission, "af.shizuku.plus.api")

        wrapper().getManagerPackages() shouldBe listOf("af.shizuku.plus.api")
    }

    @Test
    fun `collapses one app defining several permissions to a single entry`() = runTest {
        undefineAll()
        definePermission(stockPermission, "moe.shizuku.privileged.api")
        definePermission(plusPermission, "moe.shizuku.privileged.api")

        wrapper().getManagerPackages() shouldBe listOf("moe.shizuku.privileged.api")
    }

    @Test
    fun `a failing lookup for one permission does not hide the others`() = runTest {
        undefinePermission(porterPermission)
        every { packageManager.getPermissionInfo(stockPermission, any<Int>()) } throws RuntimeException("OEM quirk")
        definePermission(plusPermission, "af.shizuku.plus.api")

        wrapper().getManagerPackages() shouldBe listOf("af.shizuku.plus.api")
    }

    @Test
    fun `connection follows the SDK's connection, one handle per connection`() = runTest {
        val wrapper = wrapper()
        wrapper.connection.first() shouldBe null

        val first = FakeServer(AdbBackend.SHIZUKU)
        source.server.value = first
        val firstHandle = wrapper.connection.first()!!
        firstHandle.backend shouldBe AdbBackend.SHIZUKU
        firstHandle shouldBe AdbServer(first)

        source.server.value = FakeServer(AdbBackend.PORTER)
        val secondHandle = wrapper.connection.first()!!
        secondHandle.backend shouldBe AdbBackend.PORTER
        secondHandle shouldNotBe firstHandle
    }

    @Test
    fun `permission state follows the current connection only`() = runTest {
        val wrapper = wrapper()
        val first = FakeServer()
        val second = FakeServer()

        val seen = mutableListOf<AdbPermissionState>()
        val job = launch { wrapper.permissionState.toList(seen) }
        runCurrent()

        // No connection: nothing to ask, which is not a denial.
        seen.last() shouldBe AdbPermissionState.Unknown

        source.server.value = first
        runCurrent()
        seen.last() shouldBe AdbPermissionState.Denied(permanentlyDenied = false)
        first.permissionFlow.value = AdbPermissionState.Granted
        runCurrent()
        seen.last() shouldBe AdbPermissionState.Granted

        source.server.value = second
        runCurrent()
        seen.last() shouldBe AdbPermissionState.Denied(permanentlyDenied = false)
        // A replaced connection's late state must not leak into the current one.
        first.permissionFlow.value = AdbPermissionState.Denied(permanentlyDenied = true)
        runCurrent()
        seen.last() shouldBe AdbPermissionState.Denied(permanentlyDenied = false)

        source.server.value = null
        runCurrent()
        seen.last() shouldBe AdbPermissionState.Unknown

        job.cancel()
    }

    @Test
    fun `isGranted is unknown without a connection`() = runTest {
        wrapper().isGranted() shouldBe null
    }

    @Test
    fun `isGranted reflects the connection's permission check`() = runTest {
        val wrapper = wrapper()
        val server = FakeServer().also { source.server.value = it }

        server.onCheck = { AdbPermissionState.Granted }
        wrapper.isGranted() shouldBe true

        server.onCheck = { AdbPermissionState.Denied(permanentlyDenied = false) }
        wrapper.isGranted() shouldBe false

        server.onCheck = { AdbPermissionState.Denied(permanentlyDenied = true) }
        wrapper.isGranted() shouldBe false

        // A failed call says nothing about the grant.
        server.onCheck = { throw IllegalStateException("binder died") }
        wrapper.isGranted() shouldBe null
    }

    @Test
    fun `isGranted gives up as unknown, not denied, on a server that does not answer`() = runTest {
        val wrapper = wrapper()
        FakeServer().also {
            it.onCheck = { awaitCancellation() }
            source.server.value = it
        }

        wrapper.isGranted() shouldBe null
        currentTime shouldBeGreaterThanOrEqual ShizukuWrapper.IPC_TIMEOUT_MS
    }

    @Test
    fun `requestPermission is unknown without a connection`() = runTest {
        wrapper().requestPermission() shouldBe AdbPermissionState.Unknown
    }

    @Test
    fun `requestPermission is not cut off by the IPC bound`() = runTest {
        val wrapper = wrapper()
        FakeServer().also {
            // A user who takes their time with the prompt.
            it.onRequest = {
                delay(ShizukuWrapper.IPC_TIMEOUT_MS * 4)
                AdbPermissionState.Granted
            }
            source.server.value = it
        }

        wrapper.requestPermission() shouldBe AdbPermissionState.Granted
        currentTime shouldBeGreaterThanOrEqual ShizukuWrapper.IPC_TIMEOUT_MS * 4
    }

    @Test
    fun `requestPermission passes the answer through`() = runTest {
        val wrapper = wrapper()
        val server = FakeServer().also { source.server.value = it }

        server.onRequest = { AdbPermissionState.Denied(permanentlyDenied = true) }
        wrapper.requestPermission() shouldBe AdbPermissionState.Denied(permanentlyDenied = true)
    }

    @Test
    fun `requestPermission is unknown when the connection is lost first`() = runTest {
        val wrapper = wrapper()
        FakeServer().also {
            it.onRequest = { throw IllegalStateException("the Porter connection was lost") }
            source.server.value = it
        }

        wrapper.requestPermission() shouldBe AdbPermissionState.Unknown
    }

    @Test
    fun `availability passes the SDK's answer through`() = runTest {
        val wrapper = wrapper()

        val answer = AdbAvailability.InstalledNotConnected(AdbBackend.PORTER, "eu.darken.porter")
        source.onAvailability = { answer }

        wrapper.availability() shouldBe answer
    }

    @Test
    fun `availability is null when it does not answer in time or fails`() = runTest {
        val wrapper = wrapper()

        source.onAvailability = { awaitCancellation() }
        wrapper.availability() shouldBe null
        currentTime shouldBeGreaterThanOrEqual ShizukuWrapper.IPC_TIMEOUT_MS

        source.onAvailability = { throw IllegalStateException("OEM quirk") }
        wrapper.availability() shouldBe null
    }
}
