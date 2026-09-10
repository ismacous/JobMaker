package com.jobmaker.render

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Produit un PDF A4 a partir du HTML rendu, via le moteur d'impression
 * d'Android.
 *
 * Deux raisons de passer par l'impression plutot que par une bibliotheque de
 * generation de PDF :
 *
 *  - c'est WebView qui met en page, donc le PDF est exactement ce que l'apercu
 *    affiche ;
 *  - le PDF contient du **texte selectionnable**. Ce point n'est pas
 *    cosmetique : un PDF fabrique depuis une capture d'ecran est une image,
 *    illisible pour les logiciels de tri de candidatures, et donc ecarte avant
 *    meme d'atteindre un lecteur humain.
 *
 * On confie le travail a [PrintManager] et non aux methodes onLayout/onWrite de
 * PrintDocumentAdapter : les classes de rappel de ces methodes
 * (LayoutResultCallback, WriteResultCallback) ont des constructeurs
 * package-private dans android.print, et ne peuvent donc pas etre sous-classees
 * depuis Kotlin. PrintManager fait exactement le meme travail en interne, et
 * c'est l'API que le systeme expose officiellement. Contrepartie : la boite de
 * dialogue d'impression s'ouvre et l'utilisateur choisit
 * « Enregistrer au format PDF » puis l'emplacement.
 */
class PdfExporter(private val context: Context) {

    /**
     * WebView conservee le temps de l'impression : le moteur lui demande ses
     * pages apres l'ouverture de la boite de dialogue. La laisser ramasser par
     * le garbage collector produirait un PDF vide.
     */
    private var webViewEnCours: WebView? = null

    /**
     * Rend [html] puis ouvre la boite de dialogue d'impression.
     *
     * @param activity indispensable : [PrintManager] affiche une interface et
     *   refuse un contexte d'application.
     * @return null si tout va bien, sinon le message d'erreur a afficher.
     */
    @MainThread
    suspend fun imprimer(activity: Activity, html: String, nomDocument: String): String? =
        withContext(Dispatchers.Main) {
            runCatching {
                liberer()
                val webView = creerWebView(activity)
                webViewEnCours = webView
                try {
                    // Sans borne, une WebView qui ne signale jamais la fin de son
                    // chargement laisse la coroutine suspendue pour toujours : le
                    // bouton ne fait alors rien du tout, sans le moindre message.
                    withTimeout(CHARGEMENT_MAX_MS) { chargerHtml(webView, html) }
                } catch (_: TimeoutCancellationException) {
                    liberer()
                    return@runCatching "La mise en page du document n'a pas abouti. Reessayez."
                }

                val gestionnaire = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: error("Service d'impression indisponible sur cet appareil.")

                val attributs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "PDF", 600, 600))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build()

                gestionnaire.print(
                    nomDocument,
                    webView.createPrintDocumentAdapter(nomDocument),
                    attributs,
                )
                null
            }.getOrElse { erreur ->
                Log.e(TAG, "Impression impossible", erreur)
                webViewEnCours = null
                erreur.message ?: "Impression impossible."
            }
        }

    /** Libere la WebView une fois la boite de dialogue refermee. */
    @MainThread
    fun liberer() {
        webViewEnCours?.let { vue ->
            (vue.parent as? ViewGroup)?.removeView(vue)
            vue.destroy()
        }
        webViewEnCours = null
    }

    /**
     * La WebView est reellement ajoutee a la fenetre, en un pixel invisible.
     *
     * Une WebView detachee de toute fenetre ne garantit ni le declenchement de
     * onPageFinished ni la mise en page de son contenu : le PDF sortait vide,
     * ou le bouton restait sans effet. Un pixel transparent dans le decor suffit
     * a lui donner le cycle de vie qu'elle attend, sans rien afficher. Elle est
     * retiree par [liberer].
     */
    private fun creerWebView(activity: Activity): WebView = WebView(activity).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Rendu a largeur A4 pour que WebView n'applique pas sa mise a
        // l'echelle « mobile », qui casserait la mise en page.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        alpha = 0f
        visibility = View.VISIBLE

        val decor = activity.window?.decorView as? ViewGroup
        if (decor != null) {
            decor.addView(this, ViewGroup.LayoutParams(1, 1))
        } else {
            measure(
                View.MeasureSpec.makeMeasureSpec(PX_A4_LARGEUR, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PX_A4_HAUTEUR, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, PX_A4_LARGEUR, PX_A4_HAUTEUR)
        }
    }

    private suspend fun chargerHtml(webView: WebView, html: String) =
        suspendCancellableCoroutine { continuation ->
            webView.webViewClient = object : WebViewClient() {
                private var termine = false
                override fun onPageFinished(view: WebView, url: String?) {
                    if (termine) return
                    termine = true
                    // Une frame de repit pour laisser le moteur appliquer la
                    // mise en page avant de reclamer les pages.
                    view.postDelayed({ if (continuation.isActive) continuation.resume(Unit) }, 250)
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

    /**
     * Convertit la photo choisie en data URI, seule facon fiable de l'afficher
     * dans une WebView qui n'a acces ni au systeme de fichiers ni aux content://.
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
    }.onFailure { Log.w(TAG, "Photo illisible", it) }.getOrNull()

    private companion object {
        const val TAG = "PdfExporter"
        const val CHARGEMENT_MAX_MS = 15_000L

        // A4 a 96 dpi, la resolution de reference de WebView.
        const val PX_A4_LARGEUR = 794
        const val PX_A4_HAUTEUR = 1123
    }
}

/** Remonte la chaine des contextes jusqu'a l'Activity qui heberge l'interface. */
tailrec fun Context.trouverActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.trouverActivity()
    else -> null
}
