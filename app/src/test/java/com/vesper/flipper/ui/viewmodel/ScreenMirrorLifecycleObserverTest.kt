package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test

class ScreenMirrorLifecycleObserverTest {
    @Test fun `home and lock pause retained composition and foreground resumes eligibility`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val states = mutableListOf<Boolean>()
        owner.registry.addObserver(ScreenMirrorLifecycleObserver { states += it })
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(states.last())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(states.last())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(states.last())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertFalse(states.last())
    }
}
