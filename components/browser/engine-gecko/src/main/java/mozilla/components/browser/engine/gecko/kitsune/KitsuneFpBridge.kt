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
 * The app registers a config provider via [setProvider]. GeckoEngineSession
 * calls [onPageStart] on every navigation, which writes the current config
 * to a file in the app's data directory. The fp_injector WebExtension's
 * content_script.js fetches this file at document_start to get the
 * fingerprint config.
 *
 * Communication flow:
 *  GeckoEngineSession.onPageStart
 *    → KitsuneFpBridge.onPageStart()
 *    → writes config JSON to /data/data/.../files/kitsune_fp.json
 *    → content_script.js fetches('file:///data/.../kitsune_fp.json')
 *      OR reads from a web-accessible resource
 *
 * Alternative: the content script calls runtime.sendMessage to the
 * background script which calls sendNativeMessage to get the config.
 */
object KitsuneFpBridge {

    private const val TAG = "KitsuneFpBridge"
    private const val CONFIG_FILE = "kitsune_fp.json"

    @Volatile
    private var configProvider: (() -> String?)? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var lastConfig: String? = null

    /**
     * Register the app-side config provider and context.
     * Called from KitsuneFpInjector.install().
     */
    fun initialize(context: Context, provider: () -> String?) {
        this.context = context.applicationContext
        this.configProvider = provider
        writeConfig()
    }

    /**
     * Called by GeckoEngineSession.onPageStart on every navigation.
     * Writes the current config to a file for the content script.
     */
    fun onPageStart() {
        writeConfig()
    }

    /**
     * Write the current fingerprint config to a file in the app's
     * private data directory. The content script's background.js fetches
     * this via a web-accessible resource or native messaging.
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
