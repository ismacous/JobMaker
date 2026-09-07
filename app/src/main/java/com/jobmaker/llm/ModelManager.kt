package com.jobmaker.llm

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Un fichier GGUF present sur l'appareil. */
data class InstalledModel(
    val id: String,
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
    val fromCatalog: Boolean,
) {
    val sizeLabel: String get() = formatBytes(sizeBytes)
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Resolving(val message: String) : DownloadState
    data class Running(
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
    ) : DownloadState {
        val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.2f Go", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.0f Mo", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.0f Ko", bytes / 1024.0)
    else -> "$bytes o"
}

/**
 * Telechargement, import et suppression des modeles GGUF.
 *
 * Les fichiers vivent dans le stockage prive de l'application
 * (files/models/) : pas de permission a demander, et une desinstallation
 * nettoie tout. Le nom de fichier est l'identifiant du catalogue, de sorte
 * qu'un modele reste reconnaissable meme si le catalogue evolue.
 */
class ModelManager(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)   // un modele de 5 Go prend le temps qu'il faut
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val catalog: ModelCatalog by lazy { ModelCatalog.load(context) }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    @Volatile
    private var cancelled = mutableSetOf<String>()

    val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") } ?: emptyArray()
        _installed.value = files.sortedBy { it.name }.map { f ->
            val id = f.nameWithoutExtension
            val catalogEntry = catalog.byId(id)
            InstalledModel(
                id = id,
                displayName = catalogEntry?.name ?: id,
                file = f,
                sizeBytes = f.length(),
                fromCatalog = catalogEntry != null,
            )
        }
    }

    fun installedFile(modelId: String): File? =
        _installed.value.firstOrNull { it.id == modelId }?.file

    fun isInstalled(modelId: String): Boolean = installedFile(modelId) != null

    fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(modelsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    fun cancelDownload(modelId: String) {
        synchronized(this) { cancelled.add(modelId) }
    }

    // -----------------------------------------------------------------------
    // Telechargement
    // -----------------------------------------------------------------------

    /**
     * Telecharge un modele du catalogue. Reprend un telechargement interrompu
     * grace a l'en-tete Range : sur un fichier de plusieurs gigaoctets et un
     * reseau mobile, tout reprendre de zero serait rageant.
     */
    suspend fun download(model: CatalogModel) = telecharger(
        id = model.id,
        tailleAttendueOctets = model.approxSizeMb * 1_048_576L,
        messageResolution = "Recherche du fichier sur HuggingFace...",
        resoudreUrl = { resolveDownloadUrl(model) },
    )

    /**
     * Telecharge un GGUF depuis un lien copie a la main.
     *
     * Seule issue quand un depot du catalogue a ete renomme ou supprime et
     * qu'on ne dispose pas d'un ordinateur : on retrouve le fichier sur le site
     * de HuggingFace depuis le navigateur du telephone, on copie le lien de
     * telechargement, et on le colle ici.
     */
    suspend fun downloadFromUrl(url: String) {
        val propre = url.trim()
        if (!propre.startsWith("http://") && !propre.startsWith("https://")) {
            setState(idDepuisUrl(propre), DownloadState.Failed(
                "Ce n'est pas un lien. Un lien commence par https://"
            ))
            return
        }
        telecharger(
            id = idDepuisUrl(propre),
            tailleAttendueOctets = 0L,   // taille inconnue avant la reponse du serveur
            messageResolution = "Connexion...",
            resoudreUrl = { propre },
        )
    }

    /**
     * Identifiant deduit d'une URL. L'interface s'en sert pour suivre
     * l'avancement avant meme que le telechargement ne commence.
     */
    fun idDepuisUrl(url: String): String =
        url.substringBefore('?')
            .substringAfterLast('/')
            .removeSuffix(".gguf")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "modele-telecharge" }

    private suspend fun telecharger(
        id: String,
        tailleAttendueOctets: Long,
        messageResolution: String,
        resoudreUrl: () -> String,
    ) = withContext(Dispatchers.IO) {
        synchronized(this@ModelManager) { cancelled.remove(id) }
        setState(id, DownloadState.Resolving(messageResolution))

        val target = File(modelsDir, "$id.gguf")
        val partial = File(modelsDir, "$id.gguf.part")

        try {
            val free = freeSpaceBytes()
            if (tailleAttendueOctets > 0 && free in 1 until tailleAttendueOctets) {
                throw IOException(
                    "Espace insuffisant : ${formatBytes(tailleAttendueOctets)} necessaires, " +
                        "${formatBytes(free)} disponibles."
                )
            }

            val url = resoudreUrl()
            Log.i(TAG, "Telechargement de $id depuis $url")
            downloadTo(id, url, partial)

            if (isCancelled(id)) {
                setState(id, DownloadState.Idle)
                return@withContext
            }

            if (!verifyGguf(partial)) {
                partial.delete()
                throw IOException(
                    "Le fichier recu n'est pas un modele GGUF. Le lien pointe peut-etre vers " +
                        "une page web plutot que vers le fichier lui-meme : sur HuggingFace, " +
                        "il faut le lien du bouton de telechargement, pas celui de la page."
                )
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Impossible de finaliser le fichier.")

            refreshInstalled()
            setState(id, DownloadState.Done)
        } catch (e: Exception) {
            Log.e(TAG, "Echec du telechargement de $id", e)
            setState(id, DownloadState.Failed(e.message ?: "Erreur inconnue"))
        }
    }

    /**
     * Demande a l'API HuggingFace la liste des fichiers du depot et retient
     * celui qui correspond a la quantification voulue.
     *
     * On ne code pas le nom de fichier en dur : les depots GGUF renomment
     * regulierement leurs fichiers, et une URL figee finit toujours en 404.
     * Le nom du catalogue ne sert que de repli.
     */
    private fun resolveDownloadUrl(model: CatalogModel): String {
        val resolved = runCatching { queryHuggingFaceFile(model) }.getOrNull()
        val file = resolved ?: model.fallbackFile
        return "https://huggingface.co/${model.repo}/resolve/main/$file?download=true"
    }

    private fun queryHuggingFaceFile(model: CatalogModel): String? {
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/${model.repo}")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val root = json.parseToJsonElement(body).jsonObject
            val siblings = root["siblings"]?.jsonArray ?: return null
            val names = siblings.mapNotNull { el ->
                (el as? JsonObject)?.get("rfilename")?.jsonPrimitive?.contentOrNullSafe()
            }.filter { it.endsWith(".gguf", ignoreCase = true) }

            if (names.isEmpty()) return null
            val quant = model.quant.lowercase()

            // Les fichiers decoupes ("-00001-of-00003.gguf") demanderaient une
            // fusion ; on les ecarte et on privilegie un fichier unique.
            val single = names.filterNot { it.contains("-of-") }
            return single.firstOrNull { it.lowercase().contains(quant) }
                ?: names.firstOrNull { it.lowercase().contains(quant) && !it.contains("-of-") }
                ?: single.firstOrNull { it.lowercase().contains("q4_k_m") }
        }
    }

    private fun downloadTo(modelId: String, url: String, partial: File) {
        var alreadyDone = if (partial.exists()) partial.length() else 0L

        val builder = Request.Builder().url(url)
            .header("User-Agent", "JobMaker/1.0 (Android; personal use)")
        if (alreadyDone > 0) builder.header("Range", "bytes=$alreadyDone-")

        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 416) {           // deja complet cote serveur
                return
            }
            if (!resp.isSuccessful) {
                throw IOException("Le serveur a repondu ${resp.code}. URL : $url")
            }
            // Si le serveur ignore Range, on repart de zero pour ne pas
            // concatener deux debuts de fichier.
            val resuming = resp.code == 206
            if (!resuming && alreadyDone > 0) {
                partial.delete()
                alreadyDone = 0L
            }

            val contentLength = resp.body?.contentLength() ?: -1L
            val total = if (contentLength > 0) alreadyDone + contentLength else -1L

            val body = resp.body ?: throw IOException("Reponse vide.")
            body.byteStream().use { input ->
                java.io.FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = alreadyDone
                    var lastTick = System.currentTimeMillis()
                    var lastBytes = done
                    while (true) {
                        if (isCancelled(modelId)) return
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 400) {
                            val speed = ((done - lastBytes) * 1000L) / (now - lastTick)
                            setState(modelId, DownloadState.Running(done, total, speed))
                            lastTick = now
                            lastBytes = done
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /** Un GGUF commence toujours par la signature ASCII "GGUF". */
    private fun verifyGguf(file: File): Boolean = runCatching {
        if (file.length() < 1_000_000L) return@runCatching false
        file.inputStream().use { s ->
            val magic = ByteArray(4)
            s.read(magic)
            String(magic, Charsets.US_ASCII) == "GGUF"
        }
    }.getOrDefault(false)

    // -----------------------------------------------------------------------
    // Import manuel / suppression
    // -----------------------------------------------------------------------

    /**
     * Copie un GGUF choisi dans le stockage du telephone. Sert pour les modeles
     * hors catalogue, ou quand on prefere telecharger par Wi-Fi depuis un PC.
     */
    suspend fun importFromUri(uri: Uri, displayName: String): Result<InstalledModel> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeId = displayName.substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "modele-importe" }
                val target = File(modelsDir, "$safeId.gguf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                } ?: throw IOException("Fichier illisible.")
                if (!verifyGguf(target)) {
                    target.delete()
                    throw IOException("Ce fichier n'est pas un modele GGUF.")
                }
                refreshInstalled()
                _installed.value.first { it.file == target }
            }
        }

    suspend fun delete(modelId: String) = withContext(Dispatchers.IO) {
        installedFile(modelId)?.delete()
        File(modelsDir, "$modelId.gguf.part").delete()
        refreshInstalled()
        _downloads.update { it - modelId }
    }

    private fun isCancelled(modelId: String): Boolean =
        synchronized(this) { cancelled.contains(modelId) }

    private fun setState(modelId: String, state: DownloadState) {
        _downloads.update { it + (modelId to state) }
    }

    private companion object {
        const val TAG = "ModelManager"
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
