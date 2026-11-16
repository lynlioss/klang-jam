package app.lynli.klang.jam

import kotlinx.coroutines.CancellationException

/** Recoverable plugin and native loading failures must not terminate the service coroutine. */
internal suspend fun jamAction(failed: (Throwable) -> Unit, block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { failed(failure) }
    catch (failure: LinkageError) { failed(failure) }
}
