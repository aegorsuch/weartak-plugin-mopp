package com.weartak.plugin.mopp.host

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.weartak.plugin.mopp.Availability
import com.weartak.plugin.mopp.BuildConfig
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoppPluginTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val hostUid = 12345
    private val hostPackage = "com.aitech.weartak_civ"

    @Before fun preparePackages() {
        val signature = Signature("0123456789abcdef")
        val plugin = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        plugin.signatures = arrayOf(signature)
        plugin.applicationInfo!!.uid = Process.myUid()
        shadowOf(context.packageManager).installPackage(plugin)
        installPackage(hostPackage, hostUid, signature)
        ShadowBinder.setCallingUid(hostUid)
        MoppRuntime.detach()
    }

    private fun installPackage(name: String, uid: Int, signature: Signature) {
        val info = PackageInfo().apply {
            packageName = name
            signatures = arrayOf(signature)
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.uid = uid
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun binder(): MoppPluginBinder {
        val lifecycle = PluginLifecycleMachine()
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        return MoppPluginBinder(context, lifecycle)
    }

    private fun init(extras: Bundle = Bundle()) =
        PluginInitParcel(hostPackage, hostUid, BuildConfig.APPLICATION_ID, extras)

    @After fun clearRuntime() {
        MoppRuntime.detach()
        ShadowBinder.reset()
    }

    @Test fun capabilitiesAdvertiseOnlyExternalEditor() {
        val caps = binder().capabilities
        assertTrue(caps.externalMainActivity)
        assertFalse(caps.hostedMainUi)
        assertFalse(caps.hostedSettingsUi)
        assertFalse(caps.cotReadyEvents)
        assertFalse(caps.mapLayers)
        assertFalse(caps.deviceIntegration)
        assertFalse(caps.sharedMemory)
    }

    @Test fun missingFlagOrBinderRequiresHostUpdate() {
        val combinations = listOf(
            Bundle(),
            Bundle().apply { putBoolean("moppPpeV1", true) },
            Bundle().apply { putBinder("moppPpeControlV1", Binder()) },
        )
        combinations.forEach { extras ->
            binder().initializePluginV2(init(extras), null, null)
            assertEquals(Availability.UPDATE_WEARTAK, MoppRuntime.editor.state.value.availability)
            assertFalse(MoppRuntime.editor.state.value.canSelect)
        }
    }

    @Test fun wrongBinderDescriptorCannotPretendTypedAdapterExists() {
        binder().initializePluginV2(
            init(Bundle().apply {
                putBoolean("moppPpeV1", true)
                putBinder("moppPpeControlV1", Binder())
            }),
            null, null,
        )
        assertEquals(Availability.UPDATE_WEARTAK, MoppRuntime.editor.state.value.availability)
        assertFalse(MoppRuntime.editor.state.value.canSelect)
    }

    @Test fun typedCapabilityAutomaticallySeedsFromAuthoritativeRead() {
        val control = MoppControlStub()
        binder().initializePluginV2(
            init(Bundle().apply {
                putBoolean("moppPpeV1", true)
                putBinder("moppPpeControlV1", control)
            }),
            null, null,
        )
        assertEquals(Availability.READY, MoppRuntime.editor.state.value.availability)
        runBlocking {
            withTimeout(5000) { MoppRuntime.editor.state.first { it.saved != null } }
        }
        assertEquals("2", MoppRuntime.editor.state.value.saved!!.selection!!.wireValue)
        assertNull(MoppRuntime.editor.state.value.saved!!.savedAtEpochMillis)
        assertTrue(MoppRuntime.editor.state.value.canSelect)
        assertNull(control.lastSave)
    }

    @Test fun legacyInitDoesNotAttachOrAdvanceLifecycle() {
        val binder = binder()
        binder.initializePlugin(init(), null)
        assertEquals(PluginLifecycleState.BOUND, binder.status.state)
        assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
    }

    @Test fun v3InitUsesSameTypedCapabilityWithoutRequestingPcm() {
        val control = MoppControlStub()
        binder().initializePluginV3(
            init(Bundle().apply {
                putBoolean("moppPpeV1", true)
                putBinder("moppPpeControlV1", control)
            }),
            null, null, null,
        )
        runBlocking {
            withTimeout(5000) { MoppRuntime.editor.state.first { it.saved != null } }
        }
        assertEquals(Availability.READY, MoppRuntime.editor.state.value.availability)
        assertEquals("2", MoppRuntime.editor.state.value.saved!!.selection!!.wireValue)
    }

    @Test fun initializationDoesNotWaitForHostReadAndRevocationDiscardsItsReply() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val control = MoppControlStub().apply {
            beforeRead = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        val plugin = binder()
        try {
            plugin.initializePluginV2(
                init(Bundle().apply {
                    putBoolean("moppPpeV1", true)
                    putBinder("moppPpeControlV1", control)
                }),
                null, null,
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(MoppRuntime.editor.state.value.saved)
            assertFalse(MoppRuntime.editor.state.value.canSelect)
            plugin.revoke()
            assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
        } finally {
            release.countDown()
        }
        assertNull(MoppRuntime.editor.state.value.saved)
    }

    @Test fun controlDeathClearsSelectionAndCannotRestoreAnOldBinding() {
        val control = MoppControlStub()
        var recipient: IBinder.DeathRecipient? = null
        val remote = object : IBinder by control {
            override fun linkToDeath(value: IBinder.DeathRecipient, flags: Int) {
                recipient = value
            }

            override fun unlinkToDeath(value: IBinder.DeathRecipient, flags: Int) = true
        }
        binder().initializePluginV2(
            init(Bundle().apply {
                putBoolean("moppPpeV1", true)
                putBinder("moppPpeControlV1", remote)
            }),
            null, null,
        )
        runBlocking {
            withTimeout(5000) { MoppRuntime.editor.state.first { it.saved != null } }
        }
        checkNotNull(recipient).binderDied()
        assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
        assertNull(MoppRuntime.editor.state.value.saved)
        assertNull(MoppRuntime.hostPackage)
        assertFalse(MoppRuntime.editor.state.value.canSelect)
    }

    @Test fun mismatchedInitIdentityIsRejectedBeforeAttach() {
        val binder = binder()
        val invalid = listOf(
            PluginInitParcel(hostPackage, hostUid + 1, BuildConfig.APPLICATION_ID, Bundle()),
            PluginInitParcel("other.package", hostUid, BuildConfig.APPLICATION_ID, Bundle()),
            PluginInitParcel(hostPackage, hostUid, "other.plugin", Bundle()),
        )
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) { binder.initializePluginV2(it, null, null) }
            assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
        }
    }

    @Test fun otherPackageWithMatchingSignatureIsNotWearTak() {
        installPackage("other.plugin", hostUid + 1, Signature("0123456789abcdef"))
        ShadowBinder.setCallingUid(hostUid + 1)
        assertThrows(SecurityException::class.java) { binder().pluginInfo }
    }

    @Test fun hostWithWrongSignatureIsRejected() {
        installPackage(hostPackage, hostUid, Signature("abcdef0123456789"))
        assertThrows(SecurityException::class.java) { binder().pluginInfo }
    }

    @Test fun unbindRevokesOldBinderAndClearsLiveRuntime() {
        val controller = Robolectric.buildService(MoppPluginService::class.java).create()
        val service = controller.get()
        try {
            val intent = Intent("com.weartak.plugin.action.PLUGIN_SERVICE")
            val first = service.onBind(intent) as MoppPluginBinder
            first.initializePluginV2(init(), null, null)
            service.onUnbind(intent)
            assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
            assertNull(MoppRuntime.hostPackage)
            assertThrows(SecurityException::class.java) { first.startPlugin() }
            val next = service.onBind(intent) as MoppPluginBinder
            assertNotSame(first, next)
            assertEquals("MOPP", next.pluginInfo.displayName)
            assertThrows(SecurityException::class.java) { first.initializePluginV2(init(), null, null) }
        } finally {
            controller.destroy()
        }
    }

    @Test fun stopAndShutdownClearRuntimeWithoutSaving() {
        val binder = binder()
        binder.registerHostCallback(null)
        binder.initializePluginV2(init(), null, null)
        binder.startPlugin()
        binder.stopPlugin()
        assertEquals(Availability.DISCONNECTED, MoppRuntime.editor.state.value.availability)
        binder.shutdownPlugin()
        assertThrows(SecurityException::class.java) { binder.startPlugin() }
    }
}
