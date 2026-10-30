package com.weartak.plugin.mopp.host

import android.content.Intent
import android.content.pm.PackageManager
import com.weartak.plugin.mopp.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoppDiscoveryTest {
    @Test fun pluginMenuDiscoveryExposesServiceAndExternalActivity() {
        val context = RuntimeEnvironment.getApplication()
        val services = context.packageManager.queryIntentServices(
            Intent("com.weartak.plugin.action.PLUGIN_SERVICE")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(BuildConfig.APPLICATION_ID),
            PackageManager.GET_META_DATA,
        )
        assertEquals(1, services.size)
        val service = services.single().serviceInfo
        assertEquals(MoppPluginService::class.java.name, service.name)
        assertTrue(service.exported)
        assertEquals("com.weartak.plugin.permission.BIND_PLUGIN", service.permission)
        assertEquals("Protective posture", service.metaData.getString("com.weartak.plugin.MENU_SUBTITLE"))
        val launcher = context.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
        assertNotNull(launcher)
        assertEquals("com.weartak.plugin.mopp.MoppActivity", launcher!!.component!!.className)
    }
}
