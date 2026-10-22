package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

internal class ScreenMirrorLifecycleObserver(private val update: (Boolean) -> Unit) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        update(source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
}
