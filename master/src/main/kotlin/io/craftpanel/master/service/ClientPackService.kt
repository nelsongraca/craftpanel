package io.craftpanel.master.service

import io.craftpanel.common.BuildInfo
import io.craftpanel.master.domain.ModPinStrategy
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.EnvVarsRepository
import io.craftpanel.master.service.repo.ModRepository
import io.craftpanel.master.service.repo.ModRow
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger(ClientPackService::class.java)

private const val MODRINTH_API = "https://api.modrinth.com/v2"
private const val FABRIC_META = "https://meta.fabricmc.net/v2"
private const val QUILT_META = "https://meta.quiltmc.org/v3"
private const val FORGE_PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
private const val NEOFORGE_METADATA = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
private val USER_AGENT = "CraftPanel/${BuildInfo.version} (https://github.com/nelsongraca/craftpanel)"

/** Maximum number of concurrent Modrinth requests per export — stays comfortably inside the API rate limit. */
private const val MODRINTH_CONCURRENCY = 8

/** File types that are never the distributable artifact of a mod version. */
private val NON_RUNTIME_FILE_TYPES = setOf("sources-jar", "dev-jar", "javadoc-jar", "signature")

private val VERSION_COMPARATOR = Comparator<String> { a, b ->
    val pa = a.split('.', '-', '+')
    val pb = b.split('.', '-', '+')
    for (i in 0 until max(pa.size, pb.size)) {
        val x = pa.getOrNull(i)?.toIntOrNull()
        val y = pb.getOrNull(i)?.toIntOrNull()
        val cmp = if (x != null && y != null) x.compareTo(y) else (pa.getOrNull(i) ?: "").compareTo(pb.getOrNull(i) ?: "")
        if (cmp != 0) return@Comparator cmp
    }
    0
}

@Serializable
data class ClientPackPreview(
    @SerialName("server_name") val serverName: String,
    @SerialName("pack_file_name") val packFileName: String,
    @SerialName("mc_version") val mcVersion: String,
    @SerialName("server_type") val serverType: ServerType,
    @SerialName("loader") val loader: String,
    @SerialName("loader_version") val loaderVersion: String,
    val mods: List<ClientPackMod>,
    val skipped: List<ClientPackSkipped>
)

@Serializable
data class ClientPackMod(
    @SerialName("project_id") val projectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("version_number") val versionNumber: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Long
)

@Serializable
data class ClientPackSkipped(@SerialName("project_id") val projectId: String, @SerialName("display_name") val displayName: String, val reason: String)

/** A rendered `.mrpack` plus the filename it should be served as. */
data class ClientPackBundle(val fileName: String, val bytes: ByteArray)

/**
 * Builds a client-side Modrinth modpack (`.mrpack`) from a server's tracked mods.
 *
 * Only mods that are **required on the client** are included — Modrinth marks every version with an
 * `environment`, so server-only mods, singleplayer-only mods and client-optional enhancements are
 * filtered out. The pack carries no jars and no config overrides: it is an index of Modrinth CDN
 * downloads plus the Minecraft/loader versions Prism needs to create the instance.
 *
 * The resolved plan is cached briefly (keyed by the server's mod state) because the UI previews the
 * pack before downloading it; a second export reuses the same Modrinth lookups.
 */
class ClientPackService(
    private val serverRepository: ServerRepository,
    private val modRepository: ModRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }
    }
) {

    private data class CachedPlan(val key: String, val plan: ClientPackPlan, val fetchedAtMillis: Long)

    private val cache = ConcurrentHashMap<Uuid, CachedPlan>()

    suspend fun preview(serverId: Uuid): ClientPackPreview = plan(serverId).toPreview()

    suspend fun build(serverId: Uuid): ClientPackBundle {
        val plan = plan(serverId)
        return ClientPackBundle(fileName = plan.fileName, bytes = renderZip(plan))
    }

    private suspend fun plan(serverId: Uuid): ClientPackPlan {
        val server = serverRepository.findById(serverId)
            ?: throw NotFoundException("Server not found")
        if (!server.serverType.isModLoader) {
            throw UnprocessableException("Client modpacks are only available for Fabric, Forge, NeoForge and Quilt servers")
        }
        val mods = modRepository.listMods(serverId)
        val enabled = mods.filter { it.enabled }
        if (enabled.isEmpty()) {
            throw UnprocessableException("This server has no enabled mods to export")
        }

        val cacheKey = buildCacheKey(server, enabled)
        cache[serverId]?.let { cached ->
            if (cached.key == cacheKey && System.currentTimeMillis() - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
                return cached.plan
            }
        }

        val plan = resolve(server, enabled)
        cache[serverId] = CachedPlan(cacheKey, plan, System.currentTimeMillis())
        return plan
    }

    private suspend fun resolve(server: ServerView, mods: List<ModRow>): ClientPackPlan {
        val loader = ServerType.LOADER_BY_TYPE.getValue(server.serverType)
        val dependencyKey = loaderDependencyKey(server.serverType)
        val loaderVersion = resolveLoaderVersion(server)

        val resolutions = resolveVersions(server, loader, mods)
        val projectsNeedingFallback = resolutions
            .filter { it.version != null && it.version.environment.isNullOrBlankOrUnknown() }
            .mapNotNull { it.version?.projectId }
            .distinct()
        val projectMeta = if (projectsNeedingFallback.isEmpty()) emptyMap() else fetchProjects(projectsNeedingFallback)

        val files = mutableListOf<ClientPackFile>()
        val previewMods = mutableListOf<ClientPackMod>()
        val skipped = mutableListOf<ClientPackSkipped>()
        val usedPaths = mutableSetOf<String>()

        for (resolution in resolutions) {
            val row = resolution.row
            val version = resolution.version
            if (version == null) {
                skipped += ClientPackSkipped(row.modrinthProjectId, row.displayName, "No compatible ${loaderLabel(server.serverType)} version for Minecraft ${server.mcVersion}")
                continue
            }
            val support = clientSupport(version.environment, projectMeta[version.projectId ?: row.modrinthProjectId])
            if (!support.required) {
                skipped += ClientPackSkipped(row.modrinthProjectId, row.displayName, support.reason)
                continue
            }
            val file = selectRuntimeFile(version)
            if (file == null) {
                skipped += ClientPackSkipped(row.modrinthProjectId, row.displayName, "Modrinth version has no downloadable file")
                continue
            }
            val sha1 = file.hashes.sha1
            val sha512 = file.hashes.sha512
            if (sha1.isNullOrBlank() || sha512.isNullOrBlank()) {
                skipped += ClientPackSkipped(row.modrinthProjectId, row.displayName, "Modrinth file is missing SHA-1/SHA-512 hashes")
                continue
            }
            val fileName = safeFileName(file.filename) ?: "${row.modrinthProjectId}-${version.id}.jar"
            var path = "mods/$fileName"
            if (!usedPaths.add(path)) {
                path = "mods/${row.modrinthProjectId}-$fileName"
                if (!usedPaths.add(path)) {
                    skipped += ClientPackSkipped(row.modrinthProjectId, row.displayName, "Duplicate mod filename")
                    continue
                }
            }
            files += ClientPackFile(
                path = path,
                url = file.url,
                sha1 = sha1,
                sha512 = sha512,
                size = file.size,
                clientEnv = "required",
                serverEnv = support.server
            )
            previewMods += ClientPackMod(
                projectId = row.modrinthProjectId,
                displayName = row.displayName,
                versionId = version.id,
                versionNumber = version.versionNumber,
                fileName = fileName,
                fileSize = file.size
            )
        }

        if (files.isEmpty()) {
            throw UnprocessableException("None of this server's mods are required on the client")
        }

        return ClientPackPlan(
            serverName = server.displayName.ifBlank { server.name },
            fileName = "${safePackName(server.name)}.mrpack",
            mcVersion = server.mcVersion,
            serverType = server.serverType,
            loader = loader,
            dependencyKey = dependencyKey,
            loaderVersion = loaderVersion,
            files = files,
            previewMods = previewMods,
            skipped = skipped
        )
    }

    private suspend fun resolveVersions(server: ServerView, loader: String, mods: List<ModRow>): List<ModResolution> = coroutineScope {
        val gate = Semaphore(MODRINTH_CONCURRENCY)
        mods.map { row ->
            async {
                gate.withPermit {
                    val strategy = ModPinStrategy.fromDb(row.pinStrategy)
                    val versions = fetchVersions(row.modrinthProjectId, loader, server.mcVersion)
                    ModResolution(row, selectVersion(versions, strategy, row.pinnedVersionId))
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchVersions(projectId: String, loader: String, mcVersion: String): List<ModrinthVersion> {
        val url = buildString {
            append(MODRINTH_API)
            append("/project/")
            append(URLEncoder.encode(projectId, "UTF-8"))
            append("/version?loaders=")
            append(URLEncoder.encode("[\"$loader\"]", "UTF-8"))
            append("&game_versions=")
            append(URLEncoder.encode("[\"$mcVersion\"]", "UTF-8"))
        }
        return try {
            val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (!response.status.isSuccess()) {
                log.warn("Modrinth version lookup for '{}' returned {}", projectId, response.status)
                return emptyList()
            }
            response.body<List<ModrinthVersion>>()
        } catch (e: Exception) {
            log.error("Modrinth version lookup failed for '{}'", projectId, e)
            throw BadGatewayException("Could not reach Modrinth to resolve mod versions")
        }
    }

    private suspend fun fetchProjects(projectIds: List<String>): Map<String, ModrinthProject> {
        val result = mutableMapOf<String, ModrinthProject>()
        projectIds.chunked(50).forEach { chunk ->
            val url = "$MODRINTH_API/projects?ids=${URLEncoder.encode(chunk.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")}"
            try {
                val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
                if (!response.status.isSuccess()) {
                    log.warn("Modrinth project lookup returned {}", response.status)
                    return@forEach
                }
                response.body<List<ModrinthProject>>().forEach { result[it.id] = it }
            } catch (e: Exception) {
                log.error("Modrinth project lookup failed", e)
                throw BadGatewayException("Could not reach Modrinth to resolve mod metadata")
            }
        }
        return result
    }

    /** Mirrors itzg's `MODRINTH_PROJECTS` release-channel selection, so the pack matches the server. */
    private fun selectVersion(versions: List<ModrinthVersion>, pinStrategy: ModPinStrategy, pinnedVersionId: String?): ModrinthVersion? {
        if (pinStrategy == ModPinStrategy.PINNED) {
            val pin = pinnedVersionId ?: return null
            return versions.firstOrNull { it.id == pin || it.versionNumber == pin }
        }
        val allowed = when (pinStrategy) {
            ModPinStrategy.LATEST -> setOf("release")
            ModPinStrategy.BETA -> setOf("release", "beta")
            ModPinStrategy.ALPHA -> setOf("release", "beta", "alpha")
            ModPinStrategy.PINNED -> emptySet()
        }
        // Modrinth returns versions newest-first, so the first allowed version is the one itzg would select.
        return versions.firstOrNull { it.versionType in allowed }
    }

    private fun selectRuntimeFile(version: ModrinthVersion): ModrinthFile? {
        val candidates = version.files.filter { it.fileType == null || it.fileType !in NON_RUNTIME_FILE_TYPES }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.primary }
            ?: candidates.firstOrNull { it.fileType == null }
            ?: candidates.first()
    }

    /**
     * Strict client-side filter: only mods the client must have to play on this server. Anything that
     * is server-only, singleplayer-only, optional on the client, or redundant because the server
     * already provides it is left out of the pack.
     */
    private fun clientSupport(environment: String?, project: ModrinthProject?): ClientSupport = when (environment) {
        "client_and_server" -> ClientSupport(true, MODPACK_REQUIRED)

        "client_only" -> ClientSupport(true, MODPACK_UNSUPPORTED)

        "client_only_server_optional" -> ClientSupport(true, MODPACK_OPTIONAL)

        "client_or_server_prefers_both" -> ClientSupport(true, MODPACK_REQUIRED)

        "server_only_client_optional" -> ClientSupport(false, reason = "Optional on the client")

        "client_or_server" -> ClientSupport(false, reason = "Client or server — the server already provides it")

        "singleplayer_only" -> ClientSupport(false, reason = "Singleplayer only")

        "server_only" -> ClientSupport(false, reason = "Server-side only")

        "dedicated_server_only" -> ClientSupport(false, reason = "Dedicated server only")

        // Older versions may predate the environment field; fall back to the project's legacy sides.
        else -> when (project?.clientSide) {
            "required" -> ClientSupport(true, if (project.serverSide == "unsupported") MODPACK_UNSUPPORTED else MODPACK_OPTIONAL)
            "optional" -> ClientSupport(false, reason = "Optional on the client")
            "unsupported" -> ClientSupport(false, reason = "Server-side only")
            else -> ClientSupport(false, reason = "Client support unknown")
        }
    }

    private suspend fun resolveLoaderVersion(server: ServerView): String {
        val pinnedKey = when (server.serverType) {
            ServerType.FABRIC -> "FABRIC_LOADER_VERSION"
            ServerType.QUILT -> "QUILT_LOADER_VERSION"
            ServerType.FORGE -> "FORGE_VERSION"
            ServerType.NEOFORGE -> "NEOFORGE_VERSION"
            else -> throw UnprocessableException("Client modpacks are only available for mod loader servers")
        }
        envVarsRepository.getEnvVars(server.id)
            .firstOrNull { it.key == pinnedKey }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return when (server.serverType) {
            ServerType.FABRIC -> fetchFabricLoader(server.mcVersion)
            ServerType.QUILT -> fetchQuiltLoader(server.mcVersion)
            ServerType.FORGE -> fetchForgeLoader(server.mcVersion)
            ServerType.NEOFORGE -> fetchNeoForgeLoader(server.mcVersion)
            else -> throw UnprocessableException("Client modpacks are only available for mod loader servers")
        }
    }

    private suspend fun fetchFabricLoader(mcVersion: String): String {
        val entries = try {
            val response = client.get("$FABRIC_META/versions/loader/$mcVersion") { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (!response.status.isSuccess()) {
                throw BadGatewayException("Could not resolve the Fabric loader version for Minecraft $mcVersion")
            }
            response.body<List<FabricLoaderEntry>>()
        } catch (e: ServiceException) {
            throw e
        } catch (e: Exception) {
            log.error("Fabric meta lookup failed for MC {}", mcVersion, e)
            throw BadGatewayException("Could not resolve the Fabric loader version for Minecraft $mcVersion")
        }
        return entries.mapNotNull { it.loader }.filter { it.stable != false }.maxWithOrNull(compareBy(VERSION_COMPARATOR) { it.version })?.version
            ?: throw UnprocessableException("No Fabric loader is available for Minecraft $mcVersion")
    }

    private suspend fun fetchQuiltLoader(mcVersion: String): String {
        val entries = try {
            val response = client.get("$QUILT_META/versions/loader/$mcVersion") { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (!response.status.isSuccess()) {
                throw BadGatewayException("Could not resolve the Quilt loader version for Minecraft $mcVersion")
            }
            response.body<List<QuiltLoaderEntry>>()
        } catch (e: ServiceException) {
            throw e
        } catch (e: Exception) {
            log.error("Quilt meta lookup failed for MC {}", mcVersion, e)
            throw BadGatewayException("Could not resolve the Quilt loader version for Minecraft $mcVersion")
        }
        // Quilt meta is not ordered and publishes betas; pick the highest stable version.
        return entries.mapNotNull { it.loader?.version }.filter { '-' !in it }.maxWithOrNull(VERSION_COMPARATOR)
            ?: throw UnprocessableException("No stable Quilt loader is available for Minecraft $mcVersion")
    }

    private suspend fun fetchForgeLoader(mcVersion: String): String {
        val body = try {
            val response = client.get(FORGE_PROMOTIONS) { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (!response.status.isSuccess()) {
                throw BadGatewayException("Could not resolve the Forge version for Minecraft $mcVersion")
            }
            response.bodyAsText()
        } catch (e: ServiceException) {
            throw e
        } catch (e: Exception) {
            log.error("Forge promotions lookup failed", e)
            throw BadGatewayException("Could not resolve the Forge version for Minecraft $mcVersion")
        }
        val promos = runCatching {
            Json.parseToJsonElement(body).jsonObject["promos"]?.jsonObject
        }.getOrNull() ?: throw BadGatewayException("Forge promotions response was not understood")
        val version = promos["$mcVersion-recommended"]?.jsonPrimitive?.contentOrNull
            ?: promos["$mcVersion-latest"]?.jsonPrimitive?.contentOrNull
        return version ?: throw UnprocessableException("No Forge version is promoted for Minecraft $mcVersion")
    }

    private suspend fun fetchNeoForgeLoader(mcVersion: String): String {
        val prefix = neoForgePrefix(mcVersion)
            ?: throw UnprocessableException("Could not determine the NeoForge version scheme for Minecraft $mcVersion")
        val xml = try {
            val response = client.get(NEOFORGE_METADATA) { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (!response.status.isSuccess()) {
                throw BadGatewayException("Could not resolve the NeoForge version for Minecraft $mcVersion")
            }
            response.bodyAsText()
        } catch (e: ServiceException) {
            throw e
        } catch (e: Exception) {
            log.error("NeoForge metadata lookup failed", e)
            throw BadGatewayException("Could not resolve the NeoForge version for Minecraft $mcVersion")
        }
        val versions = Regex("<version>([^<]+)</version>").findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) && '-' !in it }
            .toList()
        return versions.maxWithOrNull(VERSION_COMPARATOR)
            ?: throw UnprocessableException("No NeoForge build is available for Minecraft $mcVersion")
    }

    /** NeoForge versions are `<minecraft minor>.<patch>.<build>` (1.21.1 → 21.1.x); 1.20.1 is the special 47.1.x line. */
    private fun neoForgePrefix(mcVersion: String): String? {
        val parts = mcVersion.split('.')
        if (parts.size < 2 || parts[0] != "1") return null
        val minor = parts[1].toIntOrNull() ?: return null
        if (minor <= 19) return null
        val patch = parts.getOrNull(2)?.substringBefore('-')?.toIntOrNull() ?: 0
        if (minor == 20 && patch == 1) return "47.1."
        return "$minor.$patch."
    }

    private fun renderZip(plan: ClientPackPlan): ByteArray {
        val index = ModpackIndex(
            formatVersion = 1,
            game = "minecraft",
            versionId = Instant.now().toString(),
            name = plan.serverName,
            summary = "Client modpack for ${plan.serverName} (${plan.mcVersion}, ${plan.loader})",
            files = plan.files.map {
                ModpackFile(
                    path = it.path,
                    hashes = ModpackHashes(sha1 = it.sha1, sha512 = it.sha512),
                    env = ModpackEnv(client = it.clientEnv, server = it.serverEnv),
                    downloads = listOf(it.url),
                    fileSize = it.size
                )
            },
            dependencies = linkedMapOf(
                "minecraft" to plan.mcVersion,
                plan.dependencyKey to plan.loaderVersion
            )
        )
        val json = PACK_JSON.encodeToString(ModpackIndex.serializer(), index)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("modrinth.index.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun ClientPackPlan.toPreview() = ClientPackPreview(
        serverName = serverName,
        packFileName = fileName,
        mcVersion = mcVersion,
        serverType = serverType,
        loader = loader,
        loaderVersion = loaderVersion,
        mods = previewMods,
        skipped = skipped
    )

    private fun buildCacheKey(server: ServerView, mods: List<ModRow>): String = buildString {
        append(server.serverType).append('|').append(server.mcVersion).append('|')
        append(server.name).append('|').append(server.displayName).append('|')
        mods.sortedBy { it.modrinthProjectId }.forEach {
            append(it.modrinthProjectId).append(':')
                .append(it.pinStrategy).append(':')
                .append(it.pinnedVersionId).append(':')
                .append(it.enabled).append(':')
                .append(it.updatedAt).append('|')
        }
    }

    private fun loaderDependencyKey(type: ServerType): String = when (type) {
        ServerType.FABRIC -> "fabric-loader"
        ServerType.QUILT -> "quilt-loader"
        ServerType.FORGE -> "forge"
        ServerType.NEOFORGE -> "neoforge"
        else -> throw UnprocessableException("Client modpacks are only available for mod loader servers")
    }

    private fun loaderLabel(type: ServerType): String = when (type) {
        ServerType.FABRIC -> "Fabric"
        ServerType.QUILT -> "Quilt"
        ServerType.FORGE -> "Forge"
        ServerType.NEOFORGE -> "NeoForge"
        else -> type.name
    }

    private fun safeFileName(name: String): String? = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f]"), "")
        .trim()
        .takeIf { it.isNotEmpty() && it != "." && it != ".." }

    private fun safePackName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-')
        .ifEmpty { "modpack" }

    private fun String?.isNullOrBlankOrUnknown(): Boolean = this.isNullOrBlank() || this == "unknown"

    private data class ClientPackPlan(
        val serverName: String,
        val fileName: String,
        val mcVersion: String,
        val serverType: ServerType,
        val loader: String,
        val dependencyKey: String,
        val loaderVersion: String,
        val files: List<ClientPackFile>,
        val previewMods: List<ClientPackMod>,
        val skipped: List<ClientPackSkipped>
    )

    private data class ClientPackFile(val path: String, val url: String, val sha1: String, val sha512: String, val size: Long, val clientEnv: String, val serverEnv: String)

    private data class ModResolution(val row: ModRow, val version: ModrinthVersion?)

    private data class ClientSupport(val required: Boolean, val server: String = MODPACK_OPTIONAL, val reason: String = "")

    private companion object {
        const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
        const val MODPACK_REQUIRED = "required"
        const val MODPACK_OPTIONAL = "optional"
        const val MODPACK_UNSUPPORTED = "unsupported"
        val PACK_JSON = Json {
            encodeDefaults = false
            prettyPrint = false
        }
    }
}

@Serializable
private data class ModrinthVersion(
    val id: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("version_number") val versionNumber: String,
    @SerialName("version_type") val versionType: String,
    val environment: String? = null,
    val files: List<ModrinthFile> = emptyList()
)

@Serializable
private data class ModrinthFile(
    val hashes: ModrinthHashes,
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long = 0,
    @SerialName("file_type") val fileType: String? = null
)

@Serializable
private data class ModrinthHashes(val sha1: String? = null, val sha512: String? = null)

@Serializable
private data class ModrinthProject(val id: String, @SerialName("client_side") val clientSide: String? = null, @SerialName("server_side") val serverSide: String? = null)

@Serializable
private data class FabricLoaderEntry(val loader: FabricLoader? = null)

@Serializable
private data class FabricLoader(val version: String, val stable: Boolean? = null)

@Serializable
private data class QuiltLoaderEntry(val loader: QuiltLoader? = null)

@Serializable
private data class QuiltLoader(val version: String)

@Serializable
private data class ModpackIndex(
    val formatVersion: Int,
    val game: String,
    val versionId: String,
    val name: String,
    val summary: String? = null,
    val files: List<ModpackFile>,
    val dependencies: Map<String, String>
)

@Serializable
private data class ModpackFile(val path: String, val hashes: ModpackHashes, val env: ModpackEnv, val downloads: List<String>, val fileSize: Long)

@Serializable
private data class ModpackHashes(val sha1: String, val sha512: String)

@Serializable
private data class ModpackEnv(val client: String, val server: String)
