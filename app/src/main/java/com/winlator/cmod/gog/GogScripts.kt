package com.winlator.cmod.gog

import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.store.StoreRedists
import com.winlator.cmod.store.StoreShortcuts
import java.io.File
import java.util.zip.CRC32
import org.json.JSONObject

/**
 * The parts of GOG install scripts (goggame-<product>.script, which GOG Galaxy's script
 * interpreter runs after an install) that games rely on:
 *
 *  - "supportData" copies files GOG ships as support data (DOSBox and ScummVM
 *    configurations, for example) from the product's support folder into the game;
 *  - "setRegistry" writes registry values; those go into each container through a .reg
 *    file the launch batch imports.
 *
 * Placeholders: {app} (the game folder), {supportDir} (its support files) and {productId}.
 * Actions using anything else are skipped.
 */
internal object GogScripts {
    private const val TAG = "GogScripts"

    /** Where the support files of each product go, inside the game folder. */
    const val SUPPORT_DIR = "__support"

    private val SCRIPT = Regex("goggame-(\\d+)\\.script", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

    private class Action(val productId: String, val name: String, val arguments: JSONObject)

    /** Runs the file actions of every script in [installDir]. */
    fun applyFiles(installDir: File) {
        for (action in actions(installDir)) {
            if (action.name != "supportData") continue
            val args = action.arguments
            val target = args.optString("target").let { resolve(it, installDir, action.productId) }
            if (target == null) {
                Log.w(TAG, "${action.productId}: skipped supportData to ${args.optString("target")}")
                continue
            }
            val source = args.optString("source").takeIf { it.isNotBlank() }?.let { resolve(it, installDir, action.productId) }
            val overwrite = args.optBoolean("overwrite", false)
            when {
                source == null && args.optString("source").isNotBlank() ->
                    Log.w(TAG, "${action.productId}: skipped supportData from ${args.optString("source")}")
                source == null -> if (args.optString("type") == "folder") target.mkdirs()
                source.isDirectory -> source.copyRecursively(target, overwrite) { _, e ->
                    if (e is FileAlreadyExistsException) OnErrorAction.SKIP else throw e
                }
                source.isFile -> if (overwrite || !target.exists()) {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                else -> Log.w(TAG, "${action.productId}: support data ${source.path} missing")
            }
        }
    }

    /**
     * The registry values of every script in [installDir] as [out] (a .reg file, with
     * {app} as [container] sees it) and the launch-batch step that imports it once per
     * container, or null when the scripts set no values.
     */
    fun registryStep(installDir: File, container: Container, out: File): StoreRedists.Redist? {
        val app = StoreShortcuts.windowsPath(container, installDir)
        val keys = linkedMapOf<String, MutableList<String>>()
        for (action in actions(installDir)) {
            if (action.name != "setRegistry") continue
            val args = action.arguments
            val root = args.optString("root").uppercase().takeIf { it.startsWith("HKEY_") }
            val subkey = windows(args.optString("subkey"), app, action.productId)
            val name = windows(args.optString("valueName"), app, action.productId)
            val data = windows(args.optString("valueData"), app, action.productId)
            val value = when (args.optString("valueType").lowercase()) {
                "string", "" -> data?.let { "\"${escape(it)}\"" }
                "dword" -> data?.toLongOrNull()?.let { "dword:%08x".format(it and 0xFFFFFFFFL) }
                else -> null
            }
            if (root == null || subkey.isNullOrBlank() || name == null || value == null) {
                Log.w(TAG, "${action.productId}: skipped setRegistry ${args.optString("subkey")}\\${args.optString("valueName")}")
                continue
            }
            val entry = (if (name.isEmpty()) "@" else "\"${escape(name)}\"") + "=$value"
            val paths = mutableListOf("$root\\$subkey")
            // Galaxy is 32-bit: under 64-bit Windows its HKLM\Software writes land in WOW6432Node.
            if (root == "HKEY_LOCAL_MACHINE" && subkey.startsWith("Software\\", true) && !subkey.contains("WOW6432Node", true)) {
                paths += "$root\\Software\\WOW6432Node\\${subkey.substring("Software\\".length)}"
            }
            paths.forEach { keys.getOrPut(it) { mutableListOf() } += entry }
        }
        if (keys.isEmpty()) return null
        val text = buildString {
            append("Windows Registry Editor Version 5.00\r\n")
            keys.forEach { (key, entries) ->
                append("\r\n[$key]\r\n")
                entries.forEach { append(it).append("\r\n") }
            }
        }
        out.parentFile?.mkdirs()
        out.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))
        val crc = CRC32().apply { update(text.toByteArray()) }.value
        return StoreRedists.Redist(out, "gog-registry-${installDir.name}-%08x".format(crc), "")
    }

    /** Install actions of every script in [installDir], for English or any language. */
    private fun actions(installDir: File): List<Action> =
        (installDir.listFiles() ?: emptyArray()).sortedBy { it.name }.flatMap { file ->
            val productId = SCRIPT.matchEntire(file.name)?.groupValues?.get(1) ?: return@flatMap emptyList()
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@flatMap emptyList()
            val actions = json.optJSONArray("actions") ?: return@flatMap emptyList()
            (0 until actions.length()).mapNotNull { i ->
                val entry = actions.optJSONObject(i) ?: return@mapNotNull null
                val languages = entry.optJSONArray("languages")
                val wanted = languages == null || languages.length() == 0 || (0 until languages.length())
                    .map { languages.optString(it).lowercase() }.any { it == "*" || it.startsWith("en") }
                val install = entry.optJSONObject("install") ?: return@mapNotNull null
                if (!wanted) return@mapNotNull null
                Action(json.optString("productId", productId), install.optString("action"), install.optJSONObject("arguments") ?: JSONObject())
            }
        }

    /** A script path as a file on the phone, or null when it uses an unknown placeholder. */
    private fun resolve(path: String, installDir: File, productId: String): File? {
        val support = File(installDir, "$SUPPORT_DIR/$productId").path
        val text = substitute(path, productId) { name ->
            when (name.lowercase()) {
                "app" -> installDir.path
                "supportdir" -> support
                else -> null
            }
        } ?: return null
        val normalized = text.replace('\\', '/')
        return if (normalized.startsWith("/")) File(normalized) else File(installDir, normalized)
    }

    /** A script value as Windows sees it, or null when it uses an unknown placeholder. */
    private fun windows(value: String, app: String, productId: String): String? =
        substitute(value, productId) { name -> if (name.equals("app", true)) app else null }?.replace('/', '\\')

    private fun substitute(text: String, productId: String, lookup: (String) -> String?): String? {
        var unknown = false
        val result = PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.equals("productId", true) -> productId
                else -> lookup(name) ?: run {
                    unknown = true
                    match.value
                }
            }
        }
        return if (unknown) null else result
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
