package com.yingti.app

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test fun updatesFromConcurrentCallbacksAreAtomic() = runBlocking {
        AppState.reset()
        coroutineScope {
            repeat(4) { launch(Dispatchers.Default) { repeat(2000) {
                AppState.update { it.copy(intensity = it.intensity + 1) }
            } } }
        }
        assertEquals(8000, AppState.state.value.intensity)
        AppState.reset()
    }
}
