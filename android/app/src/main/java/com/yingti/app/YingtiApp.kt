package com.yingti.app

import android.app.Application
import com.yingti.app.history.ActivityStore
import com.yingti.app.auth.ApiClient
import com.yingti.app.auth.TokenStore
import com.yingti.app.patterns.PatternApi
import com.yingti.app.patterns.PatternLibraryRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class YingtiApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var history: ActivityStore
        private set
    lateinit var patternLibrary: PatternLibraryRepository
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
                httpClient = ApiClient.defaultClient()
                apiClient = ApiClient(httpClient)
                history = ActivityStore(this@YingtiApp)
                patternLibrary = PatternLibraryRepository({ PatternApi(it, httpClient) }, cacheDir = File(filesDir, "pattern_cache"))
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
