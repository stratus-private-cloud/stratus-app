package dev.stratus.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The launcher's long-press menu: the parts of a Stratus, as the web app's
 * manifest offers its libraries. Only a Stratus has parts, so the menu is
 * published once the server is known and taken away when it is not.
 */
class Shortcuts {
    /** The part the app was opened at, by [Section.id]; cleared once shown. */
    val opened = MutableStateFlow<String?>(null)

    /** Told which parts there are, or none. The platform publishes them. */
    var offer: (List<Section>) -> Unit = {}

    data class Section(val id: String, val label: String)
}
