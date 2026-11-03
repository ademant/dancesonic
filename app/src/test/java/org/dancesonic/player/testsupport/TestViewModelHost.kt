package org.dancesonic.player.testsupport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.util.UUID

/**
 * Creates ViewModels through a [ViewModelStore], as the framework does. [clear] cancels their
 * viewModelScope jobs; without it, a stateIn timer from one test can still hold Dispatchers.Main when
 * the next test installs its own.
 */
internal class TestViewModelHost {
    private val store = ViewModelStore()

    inline fun <reified VM : ViewModel> create(crossinline factory: () -> VM): VM {
        val providerFactory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = factory() as T
            }
        return ViewModelProvider(store, providerFactory).get(UUID.randomUUID().toString(), VM::class.java)
    }

    fun clear() {
        store.clear()
    }
}
