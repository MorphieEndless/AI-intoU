package com.yingti.app

import android.app.Application
import com.yingti.app.history.ActivityStore
import com.yingti.app.auth.ApiClient
import com.yingti.app.auth.TokenStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class YingtiApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var history: ActivityStore
        private set

    private val initialization = CompletableDeferred<Unit>()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val initializationError = mutableError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // Keystore initialization and the initial encrypted preference read can
        // touch disk/Binder. Don't perform them on the first-frame main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                tokenStore = TokenStore(this@YingtiApp)
                tokenStore.currentConfig() // warm encrypted values before rendering
                apiClient = ApiClient()
                history = ActivityStore(this@YingtiApp)
                initialization.complete(Unit)
                mutableReady.value = true
            } catch (e: Exception) {
                mutableError.value = "无法读取安全配置，请重启应用后重试"
                initialization.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitReady() = initialization.await()
}
