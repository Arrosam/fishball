package org.areel.fishball.core

import kotlinx.coroutines.CancellationException

/**
 * Being cancelled is not a failure, and must never be reported as one.
 *
 * Everything in this app that talks to a service wraps the call in a broad catch, on the sound
 * principle that a turn should degrade rather than crash: an unreachable proxy becomes
 * 「这会儿连不上」, a dead link becomes one bad result. A `catch (e: Exception)` also catches
 * [CancellationException], though, and that one is not a thing going wrong - it is the caller
 * saying stop.
 *
 * Measured, with a stop button in front of it: tapping stop on a running turn produced
 * 「这会儿连不上，等下再问我一次吧」. The app told somebody the service was down because they
 * had told the app to stop. It is also broken concurrency - swallowing a cancellation leaves a
 * coroutine running work whose caller has already gone - but the reason to fix it is that it
 * lies to the person holding the phone about something they just did.
 *
 * So: rethrow first, catch second. These two are the shape that reads best at each site.
 */

/** [runCatching], with cancellation passing straight through. */
inline fun <T> catching(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }

/**
 * For a `try`/`catch` that has to stay a `try`/`catch`.
 *
 * Called as the first line of the handler: `catch (e: Exception) { e.notCancellation() ... }`.
 * A rethrow inside the function it is called from would be invisible at the call site, so this
 * returns nothing and throws - the name is the documentation.
 */
fun Throwable.notCancellation() {
    if (this is CancellationException) throw this
}
