package com.shusheng.cobblemarket.event

class Event<T> {
    private val listeners = mutableListOf<(T) -> Unit>()

    fun subscribe(listener: (T) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    fun trigger(data: T) {
        listeners.toList().forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                // Don't let one listener break the chain
            }
        }
    }
}

class Subscription(private val unsubscribe: () -> Unit) {
    fun close() = unsubscribe()
}
