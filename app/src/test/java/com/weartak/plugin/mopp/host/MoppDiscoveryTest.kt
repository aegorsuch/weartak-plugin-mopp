package com.weartak.plugin.mopp.host

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.VectorDrawable
import android.util.TypedValue
import com.weartak.plugin.mopp.BuildConfig
import com.weartak.plugin.mopp.R
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
        assertEquals("MOPP", context.getString(R.string.app_name))
        assertEquals(R.mipmap.mopp_launcher, context.applicationInfo.icon)
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

    @Test fun launcherIconHasNativeRasterForEveryStandardDensity() {
        val context = RuntimeEnvironment.getApplication()
        for ((qualifier, density, size) in listOf(
            Triple("ldpi", 120, 36),
            Triple("mdpi", 160, 48),
            Triple("hdpi", 240, 72),
            Triple("xhdpi", 320, 96),
            Triple("xxhdpi", 480, 144),
            Triple("xxxhdpi", 640, 192),
        )) {
            RuntimeEnvironment.setQualifiers(qualifier)
            val value = TypedValue()
            context.resources.openRawResource(R.mipmap.mopp_launcher, value).use { stream ->
                val signature = ByteArray(8)
                assertEquals(8, stream.read(signature))
                assertArrayEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                    signature,
                )
            }
            assertEquals(density, value.density)
            val bitmap = BitmapFactory.decodeResource(
                context.resources,
                R.mipmap.mopp_launcher,
                BitmapFactory.Options().apply { inScaled = false },
            )
            assertNotNull(bitmap)
            assertEquals(size, bitmap.width)
            assertEquals(size, bitmap.height)
            bitmap.recycle()
        }
        assertTrue(context.getDrawable(R.drawable.mopp_launcher) is VectorDrawable)
    }
}
