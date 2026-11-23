package com.anything2game.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking as parkingRunBlocking
import org.robolectric.shadows.ShadowLooper

/**
 * Runs [block] to completion from a test, which executes on Robolectric's main thread, without ever parking that thread.
 *
 * A plain `runBlocking` here parks the main thread, so a coroutine of the live app that resumes on the main dispatcher
 * can never run again. If that coroutine holds a lock the call needs (a [kotlinx.coroutines.sync.Mutex] kept across a
 * dispatcher hop, as `ContentLibrary.refs()` does), test and app wait for each other forever. The block runs on the IO
 * dispatcher while this thread keeps draining the main looper, as the real main thread would.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun <T> awaitIo(block: suspend CoroutineScope.() -> T): T {
    val work = GlobalScope.async(Dispatchers.IO, block = block)
    while (!work.isCompleted) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(1)
    }
    return parkingRunBlocking { work.await() }
}
