package com.winlator.cmod.steam

import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.KeyValue

/**
 * Builds the library the way the Steam client does: licenses → packages → apps, with app
 * details from PICS (the product info service). Unlike GetOwnedGames this includes free
 * games that were added to the account but never played.
 */
internal object SteamCatalog {
    private const val CHUNK = 200

    /** What WinXclipse needs to know about a game to list, size, download and launch it. */
    data class AppInfo(
        val appId: Int,
        val name: String,
        val installDir: String,
        val sizeBytes: Long,
        val depotSizes: Map<Int, Long>,
        val launch: List<SteamLaunch>,
        val dlcs: Map<Int, String>,
        val controller: SteamControllerInfo,
    )

    suspend fun ownedGames(client: SteamClient, licenses: List<License>): List<AppInfo> {
        val apps = client.getHandler(SteamApps::class.java) ?: return emptyList()
        // Package 0 is Steam's own base package (the client and tools), not a purchase.
        val packages = licenses.filter { it.packageID != 0 }
            .map { PICSRequest(it.packageID, it.accessToken) }
            .distinctBy { it.id }
        val appIds = linkedSetOf<Int>()
        for (chunk in packages.chunked(CHUNK)) {
            productInfo(apps, emptyList(), chunk).packages.values.forEach { pkg ->
                pkg.keyValues["appids"].children.mapTo(appIds) { it.asInteger() }
            }
        }
        if (appIds.isEmpty()) return emptyList()

        val tokens = apps.picsGetAccessTokens(appIds.toList(), emptyList()).await().appTokens
        val infos = mutableListOf<PICSProductInfo>()
        for (chunk in appIds.toList().chunked(CHUNK)) {
            val requests = chunk.map { PICSRequest(it, tokens[it] ?: 0L) }
            infos += productInfo(apps, requests, emptyList()).apps.values
        }
        // Owned DLC, by the game they belong to (for the Steam emulator's DLC list).
        val dlcs = mutableMapOf<Int, MutableMap<Int, String>>()
        for (app in infos) {
            val common = app.keyValues["common"]
            if (!common["type"].value.equals("dlc", ignoreCase = true)) continue
            val parent = common["parent"].asInteger(app.keyValues["extended"]["dlcforappid"].asInteger())
            if (parent != 0) dlcs.getOrPut(parent) { mutableMapOf() }[app.id] = common["name"].value.orEmpty()
        }
        return infos.mapNotNull { parseGame(it, dlcs[it.id].orEmpty()) }
    }

    /** Merges every callback of a PICS request (big requests arrive in several parts). */
    private suspend fun productInfo(apps: SteamApps, appRequests: List<PICSRequest>, packageRequests: List<PICSRequest>): Merged {
        val merged = Merged()
        apps.picsGetProductInfo(appRequests, packageRequests).await().results.forEach {
            merged.apps.putAll(it.apps)
            merged.packages.putAll(it.packages)
        }
        return merged
    }

    private class Merged {
        val apps = mutableMapOf<Int, PICSProductInfo>()
        val packages = mutableMapOf<Int, PICSProductInfo>()
    }

    private fun parseGame(app: PICSProductInfo, dlcs: Map<Int, String>): AppInfo? {
        val kv = app.keyValues
        val common = kv["common"]
        if (!common["type"].value.equals("game", ignoreCase = true)) return null
        val name = common["name"].value?.takeIf { it.isNotBlank() } ?: return null
        val depotSizes = windowsDepotSizes(kv["depots"])
        return AppInfo(
            appId = app.id,
            name = name,
            installDir = kv["config"]["installdir"].value.orEmpty(),
            sizeBytes = depotSizes.values.sum(),
            depotSizes = depotSizes,
            launch = kv["config"]["launch"].children.map { entry ->
                SteamLaunch(
                    executable = entry["executable"].value.orEmpty(),
                    arguments = entry["arguments"].value.orEmpty(),
                    workingDir = entry["workingdir"].value.orEmpty(),
                    type = entry["type"].value.orEmpty(),
                    description = entry["description"].value.orEmpty(),
                    osList = entry["config"]["oslist"].value.orEmpty(),
                    osArch = entry["config"]["osarch"].value.orEmpty(),
                    betaKey = entry["config"]["betakey"].value.orEmpty(),
                )
            },
            dlcs = dlcs,
            controller = SteamControllerInfo(
                support = common["controller_support"].value?.lowercase().orEmpty(),
                // Store categories 28/18: "Full" / "Partial Controller Support".
                storeCategory = common["category"]["category_28"].asInteger() == 1 || common["category"]["category_18"].asInteger() == 1,
                templateIndex = kv["config"]["steamcontrollertemplateindex"].asInteger(-1),
                // Most-voted first.
                tags = common["store_tags"].children.map { it.asInteger() }.filter { it > 0 },
            ),
        )
    }

    /**
     * Sizes of the depots a Windows download would fetch, with the same filters as
     * JavaSteam's DepotDownloader (Windows, 64-bit, English, no low-violence builds).
     */
    private fun windowsDepotSizes(depots: KeyValue): Map<Int, Long> {
        val sizes = mutableMapOf<Int, Long>()
        for (depot in depots.children) {
            val id = depot.name?.toIntOrNull() ?: continue
            if (depot.children.isEmpty()) continue
            val config = depot["config"]
            if (!matches(config["oslist"], "windows")) continue
            if (!matches(config["osarch"], "64")) continue
            if (!matches(config["language"], "english")) continue
            if (config["lowviolence"].asBoolean()) continue
            val public = depot["manifests"]["public"]
            val size = public["size"].asLong().takeIf { it > 0 } ?: depot["maxsize"].asLong()
            sizes[id] = size
        }
        return sizes
    }

    private fun matches(value: KeyValue, wanted: String): Boolean {
        val text = value.value
        return text.isNullOrBlank() || text.split(",").any { it.trim().equals(wanted, ignoreCase = true) }
    }
}
