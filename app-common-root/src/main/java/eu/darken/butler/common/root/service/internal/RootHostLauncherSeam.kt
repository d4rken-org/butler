package eu.darken.butler.common.root.service.internal

import android.content.Context
import android.os.Debug
import eu.darken.flowshell.core.cmd.FlowCmd
import eu.darken.flowshell.core.cmd.FlowCmdShell
import eu.darken.flowshell.core.cmd.execute
import eu.darken.flowshell.core.cmd.openSession
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

/**
 * Seams that let [RootHostLauncher.createConnection] be unit-tested without an actual rooted device.
 *
 * The launcher keeps ALL the orchestration (try/finally teardown ordering, mount-master, direct-vs-
 * relocation retry, the `connected` flag) — that's the logic under test. These three collaborators
 * are the only things that touch the OS/root boundary (a `su` shell, a broadcast receiver, and the
 * Android-only command building via [RootHostCmdBuilder]/[Debug]/Parcel), so tests can replace them
 * with fakes via the launcher's primary constructor. The real implementations below are exercised
 * end-to-end on real devices and are wired in by the launcher's @Inject (secondary) constructor.
 */
interface RootSession {
    suspend fun execute(cmd: FlowCmd): FlowCmd.Result

    /** Graceful close — writes `exit` and awaits the shell exiting. */
    suspend fun close()

    /** Forceful kill of the session. */
    suspend fun cancel()
}

interface RootSessionFactory {
    /** Opens a privileged ("su") session tied to [scope]. */
    suspend fun open(scope: CoroutineScope): RootSession
}

interface RootIpcReceiver {
    fun connect()

    /** Releases the receiver and tells the host we're leaving (sends `bye()`). */
    fun release()
}

interface RootIpcReceiverFactory {
    fun create(
        pairingCode: String,
        onConnect: (RootConnection) -> Unit,
        onDisconnect: (RootConnection) -> Unit,
    ): RootIpcReceiver
}

/** A per-connection command builder — created once so its init args (incl. [Debug] state) are frozen. */
interface RootLaunchCommand {
    fun build(withRelocation: Boolean): FlowCmd
}

interface RootLaunchCommandFactory {
    /**
     * Creates the per-connection [RootLaunchCommand]. Encapsulates [RootHostInitArgs] construction
     * (which touches [Debug] and the package name) plus [RootHostCmdBuilder] (which touches Parcel /
     * reflection), none of which run on a plain JVM — hence the seam. The args are captured ONCE here
     * so the direct-exec and relocation attempts use identical init args (matching pre-seam behaviour).
     *
     * [hostIdentity] is the caller's encoded `IpcContract.HostIdentity`, stamped into the host at
     * launch so it can be echoed back and compared later.
     */
    fun <Host : BaseRootHost> create(
        hostClass: KClass<Host>,
        pairingCode: String,
        options: RootHostOptions,
        hostIdentity: String,
    ): RootLaunchCommand
}

internal class DefaultRootSessionFactory : RootSessionFactory {
    override suspend fun open(scope: CoroutineScope): RootSession {
        val session = FlowCmdShell("su").openSession(scope).first
        return object : RootSession {
            override suspend fun execute(cmd: FlowCmd): FlowCmd.Result = cmd.execute(session)
            override suspend fun close() = session.close()
            override suspend fun cancel() = session.cancel()
        }
    }
}

internal class DefaultRootIpcReceiverFactory(
    private val context: Context,
) : RootIpcReceiverFactory {
    override fun create(
        pairingCode: String,
        onConnect: (RootConnection) -> Unit,
        onDisconnect: (RootConnection) -> Unit,
    ): RootIpcReceiver {
        val receiver = object : RootConnectionReceiver(pairingCode) {
            override fun onConnect(connection: RootConnection) = onConnect(connection)
            override fun onDisconnect(connection: RootConnection) = onDisconnect(connection)
        }
        return object : RootIpcReceiver {
            override fun connect() = receiver.connect(context)
            override fun release() = receiver.release()
        }
    }
}

internal class DefaultRootLaunchCommandFactory(
    private val context: Context,
) : RootLaunchCommandFactory {
    override fun <Host : BaseRootHost> create(
        hostClass: KClass<Host>,
        pairingCode: String,
        options: RootHostOptions,
        hostIdentity: String,
    ): RootLaunchCommand {
        // Captured once per connection — both launch attempts reuse these.
        val initArgs = RootHostInitArgs(
            pairingCode = pairingCode,
            packageName = context.packageName,
            waitForDebugger = options.isTrace && Debug.isDebuggerConnected(),
            isDebug = options.isDebug,
            isTrace = options.isTrace,
            recorderPath = options.recorderPath,
            hostIdentity = hostIdentity,
        )
        val cmdBuilder = RootHostCmdBuilder(context, hostClass)
        return object : RootLaunchCommand {
            override fun build(withRelocation: Boolean): FlowCmd =
                cmdBuilder.build(withRelocation = withRelocation, initialOptions = initArgs)
        }
    }
}
