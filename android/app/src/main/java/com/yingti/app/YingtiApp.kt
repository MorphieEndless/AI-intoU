package com.yingti.app

import android.app.Application
import com.yingti.app.auth.ApiClient
import com.yingti.app.auth.TokenStore

class YingtiApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var apiClient: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        apiClient = ApiClient()
    }
}
