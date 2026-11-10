package com.thain.duo

/**
 * A [PeekNotificationSource] fed by [PeekNotificationListenerService].
 *
 * The service and the PeekController live in the same process, so one shared instance
 * ([PeekNotificationListenerService.source]) is enough. Everything runs on the main thread:
 * NotificationListenerService delivers its callbacks there, and the controller touches views
 * from the listener callback.
 */
class ListenerNotificationSource : PeekNotificationSource {
 
    private val listeners = mutableSetOf<(List<PeekNotification>) -> Unit>()
    private var items: List<PeekNotification> = emptyList()
    private var signature: List<Pair<String, Int>> = emptyList()
 
    override fun current(): List<PeekNotification> = items
 
    override fun addListener(listener: (List<PeekNotification>) -> Unit) {
        listeners += listener
    }
 
    override fun removeListener(listener: (List<PeekNotification>) -> Unit) {
        listeners -= listener
    }
 
    /**
     * Called by the service whenever it has rebuilt the list.
     *
     * Ranking updates arrive for every notification event in the system, so most rebuilds
     * produce the same boxes. Skip those instead of redrawing the strip: two lists are "the
     * same" when they hold the same apps with the same counts, in the same order.
     */
    internal fun publish(newItems: List<PeekNotification>) {
        val newSignature = newItems.map { it.key to it.count }
        if (newSignature == signature) return
        signature = newSignature
        items = newItems
        listeners.toList().forEach { it(newItems) }
    }
}
 