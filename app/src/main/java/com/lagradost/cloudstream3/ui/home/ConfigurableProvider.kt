package com.lagradost.cloudstream3.ui.home

import android.content.Context

/**
 * A built-in [com.lagradost.cloudstream3.MainAPI] that carries its own settings screen.
 *
 * Extension providers get their settings button from [com.lagradost.cloudstream3.plugins.Plugin.openSettings],
 * shown in the extensions UI. Providers that ship with the app aren't plugins, so they have no such
 * hook. Implementing this interface makes the home provider selector show a settings gear next to the
 * provider and route it to [openSettings] — the same idea, reachable from where a built-in provider
 * actually surfaces.
 */
interface ConfigurableProvider {
    /** Open the provider's settings, e.g. as a dialog anchored to [context]. */
    fun openSettings(context: Context)
}
