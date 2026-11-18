import ai.tama.chat.ChatSessionJobs
import ai.tama.chat.ChatSetupLaunch
import ai.tama.media.ImageAttemptTracker
import ai.tama.media.VideoAttemptTracker
import ai.tama.net.ModelUseAttemptTracker
import ai.tama.sandbox.OffloadPeerBindings
import ai.tama.sandbox.OffloadPeerLaunch
import ai.tama.sandbox.OffloadPeerLease
import android.content.Context
import android.net.Credentials
import android.net.LocalSocket
import com.openminis.app.BuildConfig
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.PersistentShell
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.service.SessionConcurrencyManager
import io.github.nanomuse.sandbox.FeedSandboxScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private fun DataOutputStream.le(value: Int) = writeInt(Integer.reverseBytes(value))
private fun DataOutputStream.field(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    le(bytes.size)
    write(bytes)
}
private fun DataInputStream.le(): Int = Integer.reverseBytes(readInt())
private fun DataInputStream.field(): String = ByteArray(le()).also { readFully(it) }.toString(Charsets.UTF_8)

private val method by lazy {
    NativeOffloadServer::class.java.getDeclaredMethod("handleClient", LocalSocket::class.java, OffloadPeerLease::class.java)
        .apply { isAccessible = true }
}
private lateinit var root: File

private fun client(argv: List<String>, guestSession: String?, peer: OffloadPeerLease?): Pair<Int, String> {
    val raw = ByteArrayOutputStream()
    DataOutputStream(raw).use { out ->
        out.le(0x46464f4e); out.le(1); out.le(555); out.le(argv.size)
        argv.forEach(out::field)
        val env = guestSession?.let { listOf("MINIS_CHAT_SESSION_ID=$it") } ?: emptyList()
        out.le(env.size); env.forEach(out::field); out.field("/")
    }
    val answer = ByteArrayOutputStream()
    val socket = LocalSocket(ByteArrayInputStream(raw.toByteArray()), answer, Credentials(123))
    method.invoke(NativeOffloadServer, socket, peer)
    DataInputStream(ByteArrayInputStream(answer.toByteArray())).use { input ->
        check(input.le() == 0x52464f4e)
        val code = input.le()
        val path = input.field()
        return code to File(root, path.removePrefix("/")).readText()
    }
}

private fun awaitFile(file: File) {
    if (file.exists()) return
    FileSystems.getDefault().newWatchService().use { watcher ->
        file.parentFile.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
        if (file.exists()) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!file.exists()) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "timed out waiting for fixture file" }
            val key = watcher.poll(left, TimeUnit.NANOSECONDS) ?: error("timed out waiting for fixture file")
            key.pollEvents(); key.reset()
        }
    }
}

private fun pidOf(shell: PersistentShell): Int {
    val field = PersistentShell::class.java.getDeclaredField("process").apply { isAccessible = true }
    return (field.get(shell) as Process).pid().toInt() // Java 17 host only; not used by Android production.
}

private fun coordinatorPeer(session: String): OffloadPeerLease {
    val field = ExecutionCoordinator::class.java.getDeclaredField("shells").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val shells = field.get(ExecutionCoordinator) as Map<String, PersistentShell>
    return checkNotNull(OffloadPeerBindings.capture(pidOf(checkNotNull(shells[session]))))
}

fun main() = runBlocking {
    root = Files.createTempDirectory("tama-offload-host-").toFile()
    try {
        val context = Context(root)
        NativeOffloadServer.start(root)
        val seen = AtomicReference<NativeOffloadRequest>()
        NativeOffloadServer.register("nanomuse-media", NativeOffloadHandler { request ->
            seen.set(request)
            NativeOffloadResult(0, "handled\n")
        })

        // The real server reads a fake wire body. The peer is captured separately,
        // as it is at Android accept; a wire PID and environment cannot choose the owner.
        val peer = OffloadPeerBindings.register(32101, "chat-A") { true }
        val result = client(listOf("nanomuse-media", "image"), "chat-B", peer)
        check(result == (0 to "handled\n"))
        check(seen.get().sessionId == "chat-A")
        check(seen.get().env["MINIS_CHAT_SESSION_ID"] == "chat-A")
        seen.set(null)
        check(client(listOf("nanomuse-media", "image"), null, null).first == 2)
        check(seen.get() == null)
        check(client(listOf("nanomuse-media", "status"), "chat-B", null).first == 2)
        check(client(listOf("nanomuse-media", "status"), null, null).first == 0)
        check(seen.get().sessionId == null)

        // Negative control: after a peer is released, this host simulation
        // cannot distinguish its later socket from an unmanaged terminal if
        // the wire body omits the guest session. The real model-use handler
        // still applies its cost/mode checks; this fake does no I/O.
        val directRuns = AtomicInteger()
        NativeOffloadServer.register("minis-model-use", NativeOffloadHandler {
            directRuns.incrementAndGet()
            NativeOffloadResult(0, "fake only\n")
        })
        val retired = OffloadPeerBindings.register(32102, "chat-late") { true }
        OffloadPeerBindings.release(32102, retired)
        val missingPeer = OffloadPeerBindings.capture(32102)
        check(missingPeer == null)
        check(client(listOf("minis-model-use", "run"), "chat-late", missingPeer).first == 2)
        check(directRuns.get() == 0)
        check(client(listOf("minis-model-use", "run"), null, missingPeer).first == 0)
        check(directRuns.get() == 1)
        println("KNOWN LIMIT: a peerless model-use run without a guest session reaches the fake handler")

        // A server worker accepted before Stop pauses before its attempt registers.
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val submitted = AtomicInteger()
        NativeOffloadServer.register("nanomuse-media", NativeOffloadHandler { request ->
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            val attempt = VideoAttemptTracker.begin(request.sessionId, request.executionPeer)
            try {
                try { attempt.markPossibleSubmission(); submitted.incrementAndGet() }
                catch (_: VideoAttemptTracker.Stopped) {}
                NativeOffloadResult(0, "finished\n")
            } finally { attempt.close() }
        })
        val worker = async(Dispatchers.IO) { client(listOf("nanomuse-media", "video"), "chat-B", peer) }
        check(entered.await(5, TimeUnit.SECONDS))
        check(!VideoAttemptTracker.pauseSession("chat-A"))
        OffloadPeerBindings.release(32101, peer)
        VideoAttemptTracker.resumeSession("chat-A")
        // Even PID reuse and a new turn cannot reactivate the accepted old worker.
        val replacement = OffloadPeerBindings.register(32101, "chat-A") { true }
        proceed.countDown()
        check(withTimeout(5000) { worker.await() }.first == 0)
        check(submitted.get() == 0)
        check(OffloadPeerBindings.capture(32101) === replacement)
        OffloadPeerBindings.release(32101, peer)
        check(OffloadPeerBindings.capture(32101) === replacement)
        OffloadPeerBindings.release(32101, replacement)

        // A native image arriving after its chat job was cancelled is also refused.
        val job = Job()
        ImageAttemptTracker.pauseSession("chat-C")
        ImageAttemptTracker.pauseSession("chat-C")
        ImageAttemptTracker.resumeSession("chat-C")
        val during = ImageAttemptTracker.begin("chat-C", job)
        job.cancel()
        ImageAttemptTracker.resumeSession("chat-C")
        val after = ImageAttemptTracker.begin("chat-C", job)
        try {
            check(runCatching { during.markPossibleSubmission() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            check(runCatching { after.markPossibleSubmission() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            val next = ImageAttemptTracker.begin("chat-C", Job())
            try { next.requireActive() } finally { next.close() }
        } finally { during.close(); after.close() }

        val racers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { n ->
                val sid = "race-$n"
                val p = OffloadPeerBindings.register(40000 + n, sid) { true }
                val attempt = VideoAttemptTracker.begin(sid, p)
                val start = CountDownLatch(1)
                try {
                    val didMark = racers.submit<Boolean> {
                        start.await()
                        try { attempt.markPossibleSubmission(); true } catch (_: VideoAttemptTracker.Stopped) { false }
                    }
                    val warning = racers.submit<Boolean> { start.await(); VideoAttemptTracker.pauseSession(sid) }
                    start.countDown()
                    check(didMark.get(5, TimeUnit.SECONDS) == warning.get(5, TimeUnit.SECONDS))
                    OffloadPeerBindings.release(40000 + n, p)
                    VideoAttemptTracker.resumeSession(sid)
                    check(runCatching { attempt.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
                } finally { attempt.close(); OffloadPeerBindings.release(40000 + n, p) }
            }
        } finally { racers.shutdownNow() }

        // The fixed launcher reports its real host PID and does not run argv before ack.
        val marker = File(root, "launcher-ran")
        val text = "literal ; \$(not-a-command)"
        val launch = OffloadPeerLaunch.wrap(listOf("/bin/sh", "-c", "printf '%s' \"\$1\"; : > \"\$2\"", "child", text, marker.path), "/bin/sh")
        val p = ProcessBuilder(launch).start()
        val reported = OffloadPeerLaunch.readPid(p.inputStream)
        check(reported.toLong() == p.pid())
        check(!marker.exists())
        p.outputStream.write(OffloadPeerLaunch.ACK.toByteArray()); p.outputStream.flush()
        check(p.inputStream.readBytes().toString(Charsets.UTF_8) == text)
        check(p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && marker.exists())
        for (bad in listOf("", "0\n", "-1\n", "12345678901\n", "123")) {
            check(runCatching { OffloadPeerLaunch.readPid(ByteArrayInputStream(bad.toByteArray())) }.exceptionOrNull() is IOException)
        }

        // Compile/run the real persistent shell and coordinator with fake Android.
        // The host-only source copy substitutes /bin/sh for Android's /system/bin/sh.
        val fakeProot = File(root, "fake-proot")
        fakeProot.writeText("#!/bin/sh\n[ -z \"\$TAMA_FAKE_START\" ] || printf x >> \"\$TAMA_FAKE_START\"\nexec /bin/sh\n")
        check(fakeProot.setExecutable(true))
        RootfsManager.binary = fakeProot
        System.setProperty("tama.host.shell", "/bin/sh")
        val shell = PersistentShell(context, "host-session", emptyMap())
        shell.ensureStarted()
        val actualPid = pidOf(shell)
        val bound = checkNotNull(OffloadPeerBindings.capture(actualPid))
        check(bound.sessionId == "host-session")
        val (out, exit) = shell.executeCommand("printf shell-ok", timeout = 5000)
        check(exit == 0 && out == "shell-ok")
        shell.stop()
        check(!bound.isCurrent() && OffloadPeerBindings.capture(actualPid) == null)

        // Stop while the trusted launcher has not emitted a PID yet: it must not
        // start PRoot after Stop, even when the waiting startup coroutine continues.
        val signal = File(root, "launch-waiting")
        val release = File(root, "launch-release")
        val ran = File(root, "proot-started")
        val slowLauncher = File(root, "slow-host-shell")
        slowLauncher.writeText("#!/bin/sh\n: > \"\$TAMA_SIGNAL\"\nwhile [ ! -e \"\$TAMA_RELEASE\" ]; do /bin/sleep 0.02; done\nexec /bin/sh \"\$@\"\n")
        check(slowLauncher.setExecutable(true))
        PRootKernel.customEnvironment["TAMA_SIGNAL"] = signal.path
        PRootKernel.customEnvironment["TAMA_RELEASE"] = release.path
        PRootKernel.customEnvironment["TAMA_FAKE_START"] = ran.path
        System.setProperty("tama.host.shell", slowLauncher.path)
        val slow = PersistentShell(context, "slow-session", emptyMap())
        val starting = async(Dispatchers.IO) { runCatching { slow.ensureStarted() } }
        awaitFile(signal)
        val slowPid = pidOf(slow)
        check(OffloadPeerBindings.capture(slowPid) == null)
        slow.stop()
        release.writeText("release")
        check(withTimeout(5000) { starting.await() }.isFailure)
        check(!ran.exists() && OffloadPeerBindings.capture(slowPid) == null)
        PRootKernel.customEnvironment.clear()
        System.setProperty("tama.host.shell", "/bin/sh")

        // A command already queued inside the real coordinator may not create
        // a new process after Stop; a genuinely new command is still permitted.
        ExecutionCoordinator.init(context)
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicBoolean(true)
        FeedSandboxScope.onRefresh = {
            if (first.compareAndSet(true, false)) {
                paused.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        }
        val old = async(Dispatchers.IO) { runCatching { ExecutionCoordinator.execute("queued-session", "printf old", 5000) } }
        check(paused.await(5, TimeUnit.SECONDS))
        ExecutionCoordinator.stopCurrentCommand("queued-session")
        resume.countDown()
        check(withTimeout(5000) { old.await() }.isFailure)
        FeedSandboxScope.onRefresh = null
        val fresh = ExecutionCoordinator.execute("queued-session", "printf fresh", 5000)
        check(fresh.exitCode == 0 && fresh.output == "fresh")
        ExecutionCoordinator.stopCurrentCommand("queued-session")

        // Use the real permission manager and two real host shell processes: clearing
        // one conversation must retire its peer, attempts, witness and dialog without
        // invalidating the other conversation's pending permission or shell.
        val clearedSession = "cleared-session"
        val otherSession = "unrelated-session"
        check(ExecutionCoordinator.execute(clearedSession, "printf ready", 5000).exitCode == 0)
        check(ExecutionCoordinator.execute(otherSession, "printf other", 5000).exitCode == 0)
        val clearedPeer = coordinatorPeer(clearedSession)
        val otherPeer = coordinatorPeer(otherSession)
        val nativeOwner = Job()
        check(ChatSessionJobs.bind(clearedSession, nativeOwner))
        val image = ImageAttemptTracker.begin(clearedSession, nativeOwner, cancelJobOnStop = false)
        val video = VideoAttemptTracker.begin(clearedSession, clearedPeer)
        val unrelated = VideoAttemptTracker.begin(otherSession, otherPeer)
        val modelUse = ModelUseAttemptTracker.begin(clearedSession, clearedPeer)
        val otherModelUse = ModelUseAttemptTracker.begin(otherSession, otherPeer)
        OffloadPermissionManager.init(context)
        OffloadPermissionManager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val observation = OffloadPermissionManager.observePermission("image_generation", clearedSession)
        try {
            image.markPossibleSubmission()
            video.markPossibleSubmission()
            modelUse.markPossibleSubmission()
            val firstDialog = async(start = CoroutineStart.UNDISPATCHED) {
                OffloadPermissionManager.checkPermission("image_generation", "image", clearedSession)
            }
            val firstId = checkNotNull(OffloadPermissionManager.pendingRequest.value).requestId
            val queuedDialog = async(start = CoroutineStart.UNDISPATCHED) {
                OffloadPermissionManager.checkPermission("image_generation", "image", otherSession)
            }
            OffloadPermissionManager.clearSessionGrants(clearedSession)
            check(!withTimeout(5000) { firstDialog.await() })
            val next = withTimeout(5000) {
                OffloadPermissionManager.pendingRequest.filterNotNull().first { it.sessionId == otherSession }
            }
            // A stale response cannot approve the newly displayed dialog.
            OffloadPermissionManager.respondToRequest(firstId, OffloadPermissionManager.Response.ALLOW_SESSION)
            check(OffloadPermissionManager.pendingRequest.value?.requestId == next.requestId)
            OffloadPermissionManager.respondToRequest(next.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
            check(withTimeout(5000) { queuedDialog.await() })
            check(!observation.isCurrent() && !clearedPeer.isCurrent() && otherPeer.isCurrent())
            check(runCatching { image.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            check(runCatching { video.requireActive() }.exceptionOrNull() is VideoAttemptTracker.Stopped)
            check(runCatching { modelUse.requireActive() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
            unrelated.requireActive()
            otherModelUse.requireActive()
            val lateModelUse = ModelUseAttemptTracker.begin(clearedSession, clearedPeer)
            try {
                check(runCatching { lateModelUse.markPossibleSubmission() }.exceptionOrNull() is ModelUseAttemptTracker.Stopped)
            } finally { lateModelUse.close() }

            // A native image that reaches the tracker after cleanup is refused
            // when its owning chat job had entered the slot queue before cleanup.
            check(nativeOwner.isCancelled)
            val lateNative = ImageAttemptTracker.begin(clearedSession, nativeOwner, cancelJobOnStop = false)
            try {
                check(runCatching { lateNative.requireActive() }.exceptionOrNull() is ImageAttemptTracker.Stopped)
            } finally { lateNative.close() }
            // Negative control: code that neither reserves setup nor enters the
            // chat-slot queue remains unregistered and may arrive after cleanup.
            val unregistered = Job()
            val tooEarly = ImageAttemptTracker.begin(clearedSession, unregistered, cancelJobOnStop = false)
            try { tooEarly.requireActive() } finally { tooEarly.close(); unregistered.cancel() }
            println("KNOWN LIMIT: an unregistered setup may survive cleanup")
        } finally {
            observation.close(); image.close(); video.close(); unrelated.close(); nativeOwner.cancel()
            modelUse.close(); otherModelUse.close()
            ExecutionCoordinator.sessionDidTerminate(clearedSession)
            ExecutionCoordinator.sessionDidTerminate(otherSession)
        }

        // The actual slot manager binds waiters before suspending on its semaphore.
        val holders = (0 until SessionConcurrencyManager.MAX_CONCURRENT).map { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                val slot = SessionConcurrencyManager.acquireSlot("holder-$index")
                try { awaitCancellation() } finally { SessionConcurrencyManager.releaseSlot(slot) }
            }
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            val slot = SessionConcurrencyManager.acquireSlot("waiting-session")
            try { error("A deleted waiter must not acquire a slot") }
            finally { SessionConcurrencyManager.releaseSlot(slot) }
        }
        try {
            check("waiting-session" in SessionConcurrencyManager.suspendedSessions.value)
            OffloadPermissionManager.clearSessionGrants("waiting-session")
            check(waiter.isCancelled)
            withTimeout(5000) { waiter.join() }
            check("waiting-session" !in SessionConcurrencyManager.suspendedSessions.value)
            check(holders.all { it.isActive })
        } finally {
            waiter.cancel()
            holders.forEach { it.cancel() }
            holders.forEach { withTimeout(5000) { it.join() } }
        }

        // A reservation survives across the gap before a coroutine is attached.
        val oldReservation = ChatSessionJobs.reserve("reserved-setup")
        OffloadPermissionManager.clearSessionGrants("reserved-setup")
        val oldReservedJob = Job()
        check(!oldReservation.bind(oldReservedJob) && oldReservedJob.isCancelled)
        val freshReservation = ChatSessionJobs.reserve("reserved-setup")
        val freshReservedJob = Job()
        check(freshReservation.bind(freshReservedJob) && freshReservedJob.isActive)
        freshReservedJob.cancel()
        val duringPause = ChatSessionJobs.reserve("paused-setup")
        ChatSessionJobs.pauseSession("paused-setup")
        ChatSessionJobs.resumeSession("paused-setup")
        val pausedJob = Job()
        check(!duringPause.bind(pausedJob) && pausedJob.isCancelled)

        // An older promotion remembers a cleanup; a new one started after that
        // cleanup is not poisoned by the older witness's retained history.
        val older = ChatSessionJobs.reserve("draft-old", watchPromotion = true)
        val olderJob = Job()
        check(older.bind(olderJob))
        OffloadPermissionManager.clearSessionGrants("versioned-real")
        val newer = ChatSessionJobs.reserve("draft-new", watchPromotion = true)
        val newerJob = Job()
        check(newer.bind(newerJob))
        check(ChatSessionJobs.bindPromoted("versioned-real", newerJob))
        check(!ChatSessionJobs.bindPromoted("versioned-real", olderJob) && olderJob.isCancelled)
        check(newerJob.isActive)
        newerJob.cancel()
        val unrelatedPromotion = ChatSessionJobs.reserve("unrelated-draft", watchPromotion = true)
        val unrelatedJob = Job()
        check(unrelatedPromotion.bind(unrelatedJob))
        OffloadPermissionManager.clearSessionGrants("some-other-chat")
        check(ChatSessionJobs.bindPromoted("unrelated-real", unrelatedJob) && unrelatedJob.isActive)
        unrelatedJob.cancel()

        // Exercise the real setup/stream launcher with a dispatcher blocked before
        // the coroutine can run. Cleanup must cancel its reservation/owner, not
        // merely jobs that have already entered the slot queue.
        val setupExecutor = Executors.newSingleThreadExecutor()
        val setupDispatcher = setupExecutor.asCoroutineDispatcher()
        val setupScopeJob = SupervisorJob()
        val setupScope = CoroutineScope(setupScopeJob + setupDispatcher)
        val blockEntered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        setupExecutor.execute {
            blockEntered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
        }
        try {
            check(blockEntered.await(5, TimeUnit.SECONDS))
            val owner = AtomicReference<Job>()
            val neverStarted = CountDownLatch(1)
            val runs = AtomicInteger()
            val beforeDispatch = ChatSetupLaunch.launch(
                setupScope, "queued-setup", true,
                onOwner = { owner.set(it) }, onNeverStarted = { neverStarted.countDown() },
            ) { runs.incrementAndGet() }
            check(owner.get() === beforeDispatch)
            OffloadPermissionManager.clearSessionGrants("queued-setup")
            check(beforeDispatch.isCancelled)
            unblock.countDown()
            withTimeout(5000) { beforeDispatch.join() }
            check(neverStarted.await(5, TimeUnit.SECONDS) && runs.get() == 0)

            val started = CompletableDeferred<Unit>()
            val proceedSetup = CompletableDeferred<Unit>()
            val reachedSlot = AtomicBoolean(false)
            val preparing = ChatSetupLaunch.launch(
                setupScope, "preparing", true, onOwner = {}, onNeverStarted = {},
            ) {
                started.complete(Unit)
                proceedSetup.await()
                val slot = SessionConcurrencyManager.acquireSlot("preparing")
                try { reachedSlot.set(true) } finally { SessionConcurrencyManager.releaseSlot(slot) }
            }
            withTimeout(5000) { started.await() }
            OffloadPermissionManager.clearSessionGrants("preparing")
            withTimeout(5000) { preparing.join() }
            check(preparing.isCancelled && !reachedSlot.get())

            // The rerun's stream must remain a child of its registered setup.
            val childStarted = CompletableDeferred<Job>()
            val childFinished = CompletableDeferred<Unit>()
            val parent = ChatSetupLaunch.launch(
                setupScope, "rerun-child", true, onOwner = {}, onNeverStarted = {},
            ) {
                ChatSetupLaunch.launchStream(this, true, onOwner = {}, onFinished = { childFinished.complete(Unit) }) {
                    childStarted.complete(checkNotNull(coroutineContext[Job]))
                    awaitCancellation()
                }
            }
            val child = withTimeout(5000) { childStarted.await() }
            check(!parent.isCompleted && child.isActive)
            OffloadPermissionManager.clearSessionGrants("rerun-child")
            withTimeout(5000) { parent.join(); child.join(); childFinished.await() }
            check(parent.isCancelled && child.isCancelled)

            // Like createSession's beforeInsert callback, bind the allocated
            // id before publishing the row. Cleanup can then cancel a setup
            // which is still waiting for the insert/remaining migration work.
            val earlyBound = CompletableDeferred<Unit>()
            val permitInsert = CompletableDeferred<Unit>()
            val fakeRowInserted = AtomicBoolean(false)
            val earlyPromotion = ChatSetupLaunch.launch(
                setupScope, "early-draft", true, onOwner = {}, onNeverStarted = {}, watchPromotion = true,
            ) {
                val ownerJob = checkNotNull(coroutineContext[Job])
                check(ChatSessionJobs.bindPromoted("early-real", ownerJob))
                earlyBound.complete(Unit)
                permitInsert.await() // simulates a cancellable insert, not Room
                fakeRowInserted.set(true)
            }
            withTimeout(5000) { earlyBound.await() }
            OffloadPermissionManager.clearSessionGrants("early-real")
            permitInsert.complete(Unit)
            withTimeout(5000) { earlyPromotion.join() }
            check(earlyPromotion.isCancelled && !fakeRowInserted.get())

            // Synthetic fallback: if a different caller learns an already
            // visible id only when creation returns, the witness notices its
            // cleanup even though the job was still bound under the draft id.
            val rowVisible = CompletableDeferred<Unit>()
            val returnCreatedRow = CompletableDeferred<Unit>()
            val afterPromotedCleanup = AtomicBoolean(false)
            val promotion = ChatSetupLaunch.launch(
                setupScope, "draft-fixture", true, onOwner = {}, onNeverStarted = {}, watchPromotion = true,
            ) {
                rowVisible.complete(Unit)
                returnCreatedRow.await()
                val ownerJob = checkNotNull(coroutineContext[Job])
                if (!ChatSessionJobs.bindPromoted("persisted-fixture", ownerJob)) return@launch
                val slot = SessionConcurrencyManager.acquireSlot("persisted-fixture")
                try {
                    val image = ImageAttemptTracker.begin("persisted-fixture", ownerJob, cancelJobOnStop = false)
                    try { image.markPossibleSubmission(); afterPromotedCleanup.set(true) } finally { image.close() }
                } finally { SessionConcurrencyManager.releaseSlot(slot) }
            }
            withTimeout(5000) { rowVisible.await() }
            OffloadPermissionManager.clearSessionGrants("persisted-fixture")
            check(promotion.isActive) // real id has not yet been attached
            returnCreatedRow.complete(Unit)
            withTimeout(5000) { promotion.join() }
            check(promotion.isCancelled && !afterPromotedCleanup.get())
            val newImageMaySubmit = AtomicBoolean(false)
            val newPromotion = ChatSetupLaunch.launch(
                setupScope, "fresh-draft-fixture", true, onOwner = {}, onNeverStarted = {}, watchPromotion = true,
            ) {
                val ownJob = checkNotNull(coroutineContext[Job])
                check(ChatSessionJobs.bindPromoted("persisted-fixture", ownJob))
                val image = ImageAttemptTracker.begin("persisted-fixture", ownJob, cancelJobOnStop = false)
                try { image.markPossibleSubmission(); newImageMaySubmit.set(true) } finally { image.close() }
            }
            withTimeout(5000) { newPromotion.join() }
            check(newImageMaySubmit.get()) // no network or actual image service

            // Negative control: a path that does not reserve a promotion
            // witness can still attach the already-cleaned id afterwards.
            val legacyOwner = Job()
            check(ChatSessionJobs.bind("unwatched-draft", legacyOwner))
            OffloadPermissionManager.clearSessionGrants("unwatched-real")
            check(ChatSessionJobs.bind("unwatched-real", legacyOwner) && legacyOwner.isActive)
            legacyOwner.cancel()

            // An intentional new setup after cleanup still runs.
            val freshRan = AtomicBoolean(false)
            val fresh = ChatSetupLaunch.launch(
                setupScope, "queued-setup", true, onOwner = {}, onNeverStarted = {},
            ) { freshRan.set(true) }
            withTimeout(5000) { fresh.join() }
            check(freshRan.get() && !fresh.isCancelled)

            // The normal-package helper preserves the original unregistered path.
            val normalStarted = CompletableDeferred<Unit>()
            val normalProceed = CompletableDeferred<Unit>()
            val normalCallback = AtomicBoolean(false)
            val normalSetup = ChatSetupLaunch.launch(
                setupScope, "normal-setup", false,
                onOwner = { normalCallback.set(true) }, onNeverStarted = { normalCallback.set(true) },
            ) { normalStarted.complete(Unit); normalProceed.await() }
            withTimeout(5000) { normalStarted.await() }
            OffloadPermissionManager.clearSessionGrants("normal-setup")
            check(normalSetup.isActive && !normalCallback.get())
            normalProceed.complete(Unit)
            withTimeout(5000) { normalSetup.join() }
            check(!normalSetup.isCancelled)

            val normalLazyOwner = AtomicReference<Job>()
            val normalLazyRan = AtomicBoolean(false)
            val normalLazy = ChatSetupLaunch.launch(
                setupScope, "normal-lazy", false,
                onOwner = { normalLazyOwner.set(it) }, onNeverStarted = { error("normal callback") },
                lazyInNormal = true,
            ) { normalLazyRan.set(true) }
            withTimeout(5000) { normalLazy.join() }
            check(normalLazyOwner.get() === normalLazy && normalLazyRan.get())

            val cancelledParent = Job().also { it.cancel() }
            val deadScope = CoroutineScope(cancelledParent + setupDispatcher)
            val deadChildFinished = CompletableDeferred<Unit>()
            val deadChildRan = AtomicBoolean(false)
            val deadChild = ChatSetupLaunch.launchStream(
                deadScope, true, onOwner = {}, onFinished = { deadChildFinished.complete(Unit) },
            ) { deadChildRan.set(true) }
            withTimeout(5000) { deadChild.join(); deadChildFinished.await() }
            check(deadChild.isCancelled && !deadChildRan.get())
        } finally {
            unblock.countDown()
            setupScopeJob.cancel()
            setupDispatcher.close()
        }

        val jobRacers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { index ->
                val sid = "job-race-$index"
                val owner = Job()
                val start = CountDownLatch(1)
                val binding = jobRacers.submit<Boolean> { start.await(); ChatSessionJobs.bind(sid, owner) }
                val stopping = jobRacers.submit { start.await(); ChatSessionJobs.pauseSession(sid) }
                try {
                    start.countDown()
                    binding.get(5, TimeUnit.SECONDS); stopping.get(5, TimeUnit.SECONDS)
                    check(owner.isCancelled)
                } finally { ChatSessionJobs.resumeSession(sid); owner.cancel() }
                val newOwner = Job()
                check(ChatSessionJobs.bind(sid, newOwner) && newOwner.isActive)
                newOwner.cancel()
            }
            repeat(100) { index ->
                val sid = "reservation-race-$index"
                val reservation = ChatSessionJobs.reserve(sid)
                val owner = Job()
                val start = CountDownLatch(1)
                val binding = jobRacers.submit<Boolean> { start.await(); reservation.bind(owner) }
                val stopping = jobRacers.submit { start.await(); ChatSessionJobs.pauseSession(sid) }
                try {
                    start.countDown()
                    binding.get(5, TimeUnit.SECONDS); stopping.get(5, TimeUnit.SECONDS)
                    check(owner.isCancelled)
                } finally { ChatSessionJobs.resumeSession(sid); reservation.close(); owner.cancel() }
            }
            repeat(100) { index ->
                val draft = "promotion-draft-$index"
                val real = "promotion-real-$index"
                val reservation = ChatSessionJobs.reserve(draft, watchPromotion = true)
                val owner = Job()
                check(reservation.bind(owner))
                val start = CountDownLatch(1)
                val binding = jobRacers.submit<Boolean> { start.await(); ChatSessionJobs.bindPromoted(real, owner) }
                val stopping = jobRacers.submit { start.await(); ChatSessionJobs.pauseSession(real) }
                try {
                    start.countDown()
                    binding.get(5, TimeUnit.SECONDS); stopping.get(5, TimeUnit.SECONDS)
                    check(owner.isCancelled)
                } finally { ChatSessionJobs.resumeSession(real); reservation.close(); owner.cancel() }
            }
        } finally { jobRacers.shutdownNow() }

        // The ordinary application continues to use its original, unwrapped path.
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val normal = PersistentShell(context, "normal", emptyMap())
        normal.ensureStarted()
        val (normalOut, normalExit) = normal.executeCommand("printf normal-ok", 5000)
        check(normalExit == 0 && normalOut == "normal-ok")
        normal.stop()
        println("PASS: peers, late registration, 200 Stop races, 100 job, 100 reservation and 100 promotion races, startup and normal-package checks")
    } finally {
        FeedSandboxScope.onRefresh = null
        NativeOffloadServer.stop()
        root.deleteRecursively()
    }
}
