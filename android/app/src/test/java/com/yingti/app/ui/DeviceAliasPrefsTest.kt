package com.yingti.app.ui

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeviceAliasPrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPrefs() {
        context.getSharedPreferences("yingti_ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun setAndGetRoundTrip() {
        UiPrefs.setDeviceAlias(context, "  卧室  ")
        assertEquals("卧室", UiPrefs.deviceAlias(context))
    }

    @Test fun nullAndBlankRemoveTheKey() {
        UiPrefs.setDeviceAlias(context, "卧室")
        UiPrefs.setDeviceAlias(context, null)
        assertNull(UiPrefs.deviceAlias(context))
        UiPrefs.setDeviceAlias(context, "客厅")
        UiPrefs.setDeviceAlias(context, "   ")
        assertNull(UiPrefs.deviceAlias(context))
    }
}
