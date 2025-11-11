// Services/ServiceRegistry.kt
package com.developerobaida.accessibilitymaster.Services

import com.developerobaida.accessibilitymaster.Services.actions.ServiceAction

object ServiceRegistry {
    // key: pair(packageName, Flow) -> action instance
    private val registry = mutableMapOf<Pair<String, Flow>, ServiceAction>()

    fun register(pkg: String, flow: Flow, action: ServiceAction) {
        registry[pkg to flow] = action
    }

    fun get(pkg: String, flow: Flow): ServiceAction? = registry[pkg to flow]
}
