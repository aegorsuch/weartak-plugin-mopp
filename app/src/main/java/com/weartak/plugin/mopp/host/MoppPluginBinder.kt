package com.weartak.plugin.mopp.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.weartak.plugin.mopp.BuildConfig
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

class MoppPluginBinder(
    private val context: Context,
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {
    @Volatile private var revoked = false

    private fun authorize() {
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        val trustedHost = packages.any {
            it in HOST_PACKAGES &&
                context.packageManager.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH
        }
        if (revoked || !trustedHost) {
            Log.w(TAG, "Rejected caller or revoked plugin binding")
            throw SecurityException("WearTAK plugin binding is not authorized")
        }
    }

    @Synchronized
    fun revoke() {
        revoked = true
        MoppRuntime.detach()
    }

    override fun getPluginInfo(): PluginInfoParcel {
        authorize()
        return PluginInfoParcel(
            pluginId = BuildConfig.APPLICATION_ID,
            displayName = "MOPP",
            versionName = BuildConfig.VERSION_NAME,
            packageName = BuildConfig.APPLICATION_ID,
            description = "Host-backed protective posture editor",
        )
    }

    override fun getCapabilities(): PluginCapabilitiesParcel {
        authorize()
        return PluginCapabilitiesParcel(
            hostedSettingsUi = false, hostedMainUi = false,
            externalMainActivity = true, externalSettingsActivity = false,
            cotReadyEvents = false, mapLayers = false, deviceIntegration = false, sharedMemory = false,
        )
    }

    override fun getApiVersion(): Int {
        authorize()
        return WearTakPluginContract.CONTRACT_API_VERSION
    }

    override fun getStatus(): PluginStatusParcel {
        authorize()
        return PluginStatusParcel(lifecycle.snapshot(), message = MoppRuntime.editor.state.value.availability.name)
    }

    override fun initializePlugin(init: PluginInitParcel?, pluginControl: IWearTakPluginControl?) {
        authorize()
        // Legacy initializer is deliberately inert, matching the SDK v2 startup convention.
    }

    @Synchronized
    override fun initializePluginV2(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
    ) {
        authorize()
        requireNotNull(init) { "WearTAK initialization is required" }
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        require(init.hostUid == Binder.getCallingUid() && init.hostPackageName in HOST_PACKAGES &&
            init.hostPackageName in packages && init.pluginIdExpected == BuildConfig.APPLICATION_ID) {
            "WearTAK initialization identity does not match the binding"
        }
        MoppRuntime.initialize(init)
        lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED)
    }

    override fun initializePluginV3(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
        pluginControlV3: IWearTakPluginHostControlV3?,
    ) {
        initializePluginV2(init, pluginControl, pluginControlV2)
    }

    @Synchronized
    override fun startPlugin() {
        authorize()
        lifecycle.dispatch(PluginLifecycleEvent.HOST_STARTED)
    }

    @Synchronized
    override fun stopPlugin() {
        authorize()
        lifecycle.dispatch(PluginLifecycleEvent.HOST_STOPPED)
        MoppRuntime.detach()
    }

    @Synchronized
    override fun shutdownPlugin() {
        authorize()
        lifecycle.dispatch(PluginLifecycleEvent.HOST_SHUTDOWN)
        revoke()
    }

    @Synchronized
    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        authorize()
        lifecycle.dispatch(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        authorize()
    }

    override fun getSettingsSchema(): SettingsSchemaParcel {
        authorize()
        return SettingsSchemaParcel(title = "MOPP", fields = emptyList())
    }

    override fun getSettingsValues(): Bundle {
        authorize()
        return Bundle()
    }

    override fun onHostSettingsChanged(hostSettings: HostSettingsParcel?) {
        authorize()
    }

    override fun applySettings(values: Bundle?) {
        authorize()
        require(values == null || values.isEmpty) { "MOPP saves only through its typed host capability" }
    }

    override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
        authorize()
        val empty = values == null || values.isEmpty
        val validMode = applyMode in setOf(
            SettingsApplyMode.PARTIAL_PATCH,
            SettingsApplyMode.FULL_REPLACEMENT,
            SettingsApplyMode.HOST_DRIVEN_RECOMPUTE,
        )
        if (!empty || !validMode) Log.w(TAG, "Rejected unsupported MOPP settings mutation")
        return SettingsApplyResultParcel(
            success = empty && validMode,
            applyMode = applyMode, values = Bundle(), restartRequired = false, anyValueChanged = false,
            errors = if (empty && validMode) emptyList() else listOf(
                SettingsValidationErrorParcel(
                    fieldId = "", code = "UNSUPPORTED",
                    message = "MOPP saves only through its typed host capability",
                ),
            ),
        )
    }

    override fun getMainUiSchema(): PluginMainUiSchemaParcel {
        authorize()
        return PluginMainUiSchemaParcel(cards = emptyList())
    }

    override fun getMainUiState(): Bundle {
        authorize()
        return Bundle()
    }

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        authorize()
        Log.w(TAG, "Unsupported hosted action; use the MOPP activity")
    }

    companion object {
        private const val TAG = "MoppPlugin"
        val HOST_PACKAGES = setOf("com.aitech.weartak_civ", "com.aitech.weartak_civ.dev")
    }
}
