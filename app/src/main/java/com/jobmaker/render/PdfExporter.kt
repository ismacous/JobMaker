package com.jobmaker.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fabrique un PDF A4 a partir du HTML rendu.
 *
 * On passe par le moteur d'impression d'Android plutot que par une
 * bibliotheque de generation de PDF : c'est WebView qui met en page, donc le
 * PDF exporte est exactement ce que l'apercu affiche, avec du texte selectionnable
 * et donc lisible par les logiciels de tri de candidatures. Un PDF fabrique a
 * partir d'une capture d'ecran serait, lui, une image : illisible pour un robot,
 * et donc ecarte avant meme d'etre lu par un humain.
 */
class PdfExporter(private val context: Context) {

    suspend fun htmlVersPdf(html: String, fichierSortie: File): File =
        withContext(Dispatchers.Main) {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.defaultFontSize = 16
                // Un rendu a largeur A4 evite que WebView n'applique sa mise a
                // l'echelle "mobile" et ne casse la mise en page.
                measure(PX_A4_LARGEUR, PX_A4_HAUTEUR)
                layout(0, 0, PX_A4_LARGEUR, PX_A4_HAUTEUR)
            }

            try {
                chargerHtml(webView, html)
                imprimer(webView, fichierSortie)
            } finally {
                webView.destroy()
            }
            fichierSortie
        }

    private suspend fun chargerHtml(webView: WebView, html: String) =
        suspendCancellableCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                private var termine = false
                override fun onPageFinished(view: WebView, url: String?) {
                    if (termine) return
                    termine = true
                    // Laisse une frame au moteur pour appliquer la mise en page.
                    view.postDelayed({ if (cont.isActive) cont.resume(Unit) }, 250)
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

    private suspend fun imprimer(webView: WebView, fichierSortie: File) =
        suspendCancellableCoroutine { cont ->
            val attributs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "PDF", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()

            val adapter: PrintDocumentAdapter =
                webView.createPrintDocumentAdapter(fichierSortie.nameWithoutExtension)

            adapter.onLayout(
                attributs, attributs, CancellationSignal(),
                object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: android.print.PrintDocumentInfo, changed: Boolean) {
                        val pfd = runCatching {
                            fichierSortie.parentFile?.mkdirs()
                            if (fichierSortie.exists()) fichierSortie.delete()
                            fichierSortie.createNewFile()
                            ParcelFileDescriptor.open(
                                fichierSortie,
                                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
                            )
                        }.getOrElse {
                            if (cont.isActive) cont.resumeWithException(it)
                            return
                        }

                        adapter.onWrite(
                            arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(),
                            object : PrintDocumentAdapter.WriteResultCallback() {
                                override fun onWriteFinished(pages: Array<out PageRange>) {
                                    runCatching { pfd.close() }
                                    adapter.onFinish()
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onWriteFailed(error: CharSequence?) {
                                    runCatching { pfd.close() }
                                    adapter.onFinish()
                                    if (cont.isActive) cont.resumeWithException(
                                        RuntimeException("Ecriture du PDF impossible : $error")
                                    )
                                }

                                override fun onWriteCancelled() {
                                    runCatching { pfd.close() }
                                    adapter.onFinish()
                                    if (cont.isActive) cont.resumeWithException(
                                        RuntimeException("Export annule.")
                                    )
                                }
                            },
                        )
                    }

                    override fun onLayoutFailed(error: CharSequence?) {
                        if (cont.isActive) cont.resumeWithException(
                            RuntimeException("Mise en page impossible : $error")
                        )
                    }
                },
                null,
            )
        }

    /**
     * Convertit la photo choisie en data URI, seule facon fiable de l'afficher
     * dans un WebView qui n'a acces ni au systeme de fichiers ni aux content://.
     */
    fun photoEnDataUri(uri: String, tailleMax: Int = 600): String? = runCatching {
        if (uri.isBlank()) return null
        val source = Uri.parse(uri)
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bornes)
        }
        var echelle = 1
        while (bornes.outWidth / echelle > tailleMax || bornes.outHeight / echelle > tailleMax) {
            echelle *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = echelle }
        val bitmap = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val sortie = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, sortie)
        bitmap.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(sortie.toByteArray(), Base64.NO_WRAP)
    }.onFailure { Log.w("PdfExporter", "Photo illisible", it) }.getOrNull()

    private companion object {
        // A4 a 96 dpi, la resolution de reference de WebView.
        const val PX_A4_LARGEUR = 794
        const val PX_A4_HAUTEUR = 1123
    }
}
