package com.weartak.plugin.mopp.host

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.mopp.Availability
import com.weartak.plugin.mopp.MoppEditor
import com.weartak.plugin.sdk.mopp.MoppPpeContract
import com.weartak.plugin.sdk.mopp.aidl.IMoppPpeControlV1
import com.weartak.plugin.sdk.model.PluginInitParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object MoppRuntime {
    val editor = MoppEditor()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var hostPackage: String? = null
        private set

    private var controlBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Synchronized
    fun initialize(init: PluginInitParcel) {
        detach()
        hostPackage = init.hostPackageName
        val extras = init.hostExtras
        val binder = extras?.getBinder(MoppPpeContract.EXTRA_CONTROL)
        if (extras?.getBoolean(MoppPpeContract.EXTRA_CAPABILITY, false) != true || binder == null) {
            editor.disconnect(Availability.UPDATE_WEARTAK)
            return
        }
        try {
            if (binder.interfaceDescriptor != IMoppPpeControlV1.DESCRIPTOR) {
                Log.w("MoppRuntime", "Host advertised an incompatible MOPP control")
                editor.disconnect(Availability.UPDATE_WEARTAK)
                return
            }
            val recipient = IBinder.DeathRecipient {
                synchronized(this) {
                    if (controlBinder === binder) detach()
                }
            }
            binder.linkToDeath(recipient, 0)
            controlBinder = binder
            deathRecipient = recipient
            editor.attach(SdkMoppHost(IMoppPpeControlV1.Stub.asInterface(binder)))
            // Return from initialization without waiting on a re-entrant host Binder call.
            worker.launch { editor.read() }
        } catch (error: RemoteException) {
            Log.w("MoppRuntime", "MOPP host connection is unavailable", error)
            detach()
        }
    }

    @Synchronized
    fun detach() {
        val recipient = deathRecipient
        if (recipient != null) controlBinder?.unlinkToDeath(recipient, 0)
        deathRecipient = null
        controlBinder = null
        hostPackage = null
        editor.disconnect()
    }
}
