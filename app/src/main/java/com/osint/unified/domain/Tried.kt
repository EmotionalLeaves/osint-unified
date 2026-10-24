package com.osint.unified.domain

import kotlinx.coroutines.CancellationException

/**
 * The result, unless what failed was the caller stopping us.
 *
 * `runCatching` catches `Throwable`, and in a coroutine that includes
 * the `CancellationException` the machinery throws to unwind a job.
 * The failure then comes back looking exactly like a source that did
 * not answer, the caller carries on, and everything after it runs
 * inside a coroutine that is already cancelled — where the first
 * suspend call throws again, this time somewhere nobody is catching.
 *
 * In this app that showed up as rows stuck at RUNNING. `runTool` has a
 * `catch (CancellationException)` written under `NonCancellable`
 * precisely so that pressing STOP stamps the row cancelled; eight
 * tools wrapped their work in a bare `runCatching`, so that branch
 * never ran, control fell through to the suspending `update` call, and
 * that threw at once because the coroutine was already gone. The row
 * was never written at all. It stayed RUNNING until the next process
 * start relabelled it "interrupted", and a dossier exported in between
 * said a source was still running that had been stopped minutes
 * earlier. The username sweep — the slowest and most-used tool here,
 * and so the one most likely to be stopped — was among the eight.
 *
 * So: keep what a source can do to us, and hand back what we did.
 */
fun <T> Result<T>.orStop(): Result<T> {
    val failure = exceptionOrNull()
    if (failure is CancellationException) throw failure
    return this
}
