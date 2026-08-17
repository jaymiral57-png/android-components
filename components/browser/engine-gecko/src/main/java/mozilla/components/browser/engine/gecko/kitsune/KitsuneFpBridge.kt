/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.gecko.kitsune

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Static bridge between the app module's fingerprint injector and the
 * GeckoEngineSession (which can't depend on the app module directly).
 *
 * The app registers a script provider via [initialize]. GeckoEngineSession
 * calls [getInjectionScript] on every navigation (onPageStart), which
 * returns the full JS override script to inject into the page via
 * GeckoSession.loadUri("javascript:...") — the ONLY reliable way to run
 * JS in the page's main world at document_start under GeckoView.
 *
 * Communication flow:
 *  GeckoEngineSession.onPageStart
 *    → KitsuneFpBridge.getInjectionScript()
 *    → provider() returns the full JS script (built by KitsuneFpInjector)
 *    → GeckoEngineSession calls session.loadUri("javascript:" + script)
 *    → script executes in the PAGE's own compartment before page scripts
 *
 * The WebExtension content_script.js is kept as a fallback for sub-frames
 * and late navigations, but the evaluateJavaScript/loadUri path is the
 * primary injection mechanism.
 */
object KitsuneFpBridge {

    private const val TAG = "KitsuneFpBridge"
    private const val CONFIG_FILE = "kitsune_fp.json"

    @Volatile
    private var scriptProvider: (() -> String?)? = null

    @Volatile
    private var configProvider: (() -> String?)? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var lastConfig: String? = null

    /**
     * Register the app-side injection script provider and context.
     * Called from KitsuneFpInjector.install().
     *
     * The [scriptProvider] should return the FULL JavaScript override
     * script (wrapped in an IIFE) ready to be prefixed with "javascript:".
     * Returns null when no profile is active (no injection).
     */
    fun initialize(context: Context, scriptProvider: () -> String?) {
        this.context = context.applicationContext
        this.scriptProvider = scriptProvider
        // Keep the legacy config-file writer for the WebExtension fallback
        writeConfig()
    }

    /** Legacy: also accept a plain config provider for the WebExtension path. */
    fun initializeLegacy(context: Context, configProvider: () -> String?) {
        this.context = context.applicationContext
        this.configProvider = configProvider
        writeConfig()
    }

    /**
     * Returns the config JSON string to set as data-kitsune-fp attribute.
     * GeckoEngineSession.onPageStart calls this and uses evaluateJavaScript
     * to set the attribute on document.documentElement. The content script
     * (running at document_start) reads it and injects a <script> element
     * into the page's main world.
     * Returns null if no profile is active.
     */
    fun getConfigJson(): String? {
        return configProvider?.invoke()
    }

    /** Legacy alias kept for compatibility. */
    fun getInjectionScript(): String? {
        return scriptProvider?.invoke()
    }

    /**
     * Legacy: called by GeckoEngineSession.onPageStart to refresh the
     * on-disk config file for the WebExtension content script fallback.
     */
    fun onPageStart() {
        writeConfig()
    }

    /**
     * Write the current fingerprint config to a file in the app's
     * private data directory. The content script's background.js fetches
     * this via native messaging as a fallback path.
     */
    private fun writeConfig() {
        try {
            val config = configProvider?.invoke() ?: return
            if (config == lastConfig) return // skip if unchanged
            lastConfig = config
            val ctx = context ?: return
            val file = File(ctx.filesDir, CONFIG_FILE)
            file.writeText(config)
            Log.d(TAG, "Wrote FP config to ${file.absolutePath} (${config.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write FP config", e)
        }
    }
}
