package alexandria.core.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Wall clock for request budgets and cool-downs. Production code uses the system clock; tests install one that
 * follows the coroutine test scheduler so budgets and virtual delays agree.
 */
class NetClock(val now: () -> Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NetClock>
}

internal suspend fun netNow(): Long = (currentCoroutineContext()[NetClock]?.now ?: System::currentTimeMillis)()

/**
 * The time one provider operation (a search, a chapter list, a page list) may take, carried in the coroutine
 * context so every request the operation makes can see it:
 *
 *  - [deadlineAt] bounds the whole operation, including time spent waiting locally for a rate-limit token or a
 *    service cool-down. Waits that cannot finish before it fail at once instead of sleeping into a timeout.
 *  - [transportTimeoutMs] bounds each network exchange (DNS, connect, headers *and* body) and starts only when the
 *    request is actually sent, so a queue wait never eats into the time a slow server is given.
 *
 * Counters tell a timeout spent queueing locally (not the provider's fault) from one spent on the network.
 */
class RequestBudget(
    val owner: String,
    val deadlineAt: Long,
    val transportTimeoutMs: Long,
    private val parent: RequestBudget? = null,
) : AbstractCoroutineContextElement(Key) {
    val queueWaitMs = AtomicLong()
    val requestsStarted = AtomicInteger()
    internal val waiting = AtomicInteger()

    val isQueued: Boolean get() = waiting.get() > 0

    fun remainingMs(now: Long): Long = minOf(deadlineAt - now, parent?.remainingMs(now) ?: Long.MAX_VALUE)

    internal inline fun <T> queued(block: () -> T): T {
        waiting.incrementAndGet()
        try { return block() } finally { waiting.decrementAndGet() }
    }

    internal fun startedRequest() {
        requestsStarted.incrementAndGet()
        (parent)?.requestsStarted?.incrementAndGet()
    }

    internal fun addQueueWait(ms: Long) {
        queueWaitMs.addAndGet(ms)
        parent?.queueWaitMs?.addAndGet(ms)
    }

    companion object Key : CoroutineContext.Key<RequestBudget>
}

/**
 * A provider operation exceeded the time it was given. This is an ordinary, recoverable failure of *that*
 * provider (callers fall back to another release or serve cached data) and deliberately **not** a
 * `CancellationException`: cancellation of the caller (the user left the screen, an enclosing deadline expired)
 * still propagates untouched.
 */
class ProviderTimeoutException(val owner: String, val timeoutMs: Long, val phase: Phase) :
    InterruptedIOException("$owner did not answer within ${timeoutMs / 1000.0}s" + if (phase == Phase.QUEUED) " (waiting for a local request slot)" else "") {
    enum class Phase {
        /** Expired while waiting for a local rate-limit token or cool-down; no request had been sent. */
        QUEUED,

        /** Expired while the provider was being asked. */
        NETWORK,
    }
}

private class Box<T>(val value: T)

/**
 * Runs [block] under its own deadline and a [RequestBudget]. Only *this* deadline is converted into a
 * [ProviderTimeoutException]; if the caller's coroutine is cancelled or an enclosing deadline fires first, that
 * cancellation propagates unchanged.
 */
suspend fun <T> withProviderTimeout(owner: String, timeoutMs: Long, transportTimeoutMs: Long = timeoutMs, block: suspend () -> T): T {
    val parent = currentCoroutineContext()[RequestBudget]
    val budget = RequestBudget(owner, netNow() + timeoutMs, transportTimeoutMs.coerceAtMost(timeoutMs), parent)
    val result = withTimeoutOrNull(timeoutMs) { Box(withContext(budget) { block() }) }
    if (result == null) {
        // withTimeoutOrNull also returns null after an enclosing cancellation raced with ours: never hide that.
        currentCoroutineContext().ensureActive()
        val phase = if (budget.isQueued && budget.requestsStarted.get() == 0) ProviderTimeoutException.Phase.QUEUED else ProviderTimeoutException.Phase.NETWORK
        throw ProviderTimeoutException(owner, timeoutMs, phase)
    }
    return result.value
}
