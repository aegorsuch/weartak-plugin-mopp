package com.weartak.plugin.mopp.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine

class MoppPluginService : Service() {
    private var binder: MoppPluginBinder? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != "com.weartak.plugin.action.PLUGIN_SERVICE") return null
        binder?.revoke()
        val lifecycle = PluginLifecycleMachine()
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        return MoppPluginBinder(this, lifecycle).also { binder = it }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        binder?.revoke()
        binder = null
        return false
    }

    override fun onDestroy() {
        binder?.revoke()
        binder = null
        super.onDestroy()
    }
}
