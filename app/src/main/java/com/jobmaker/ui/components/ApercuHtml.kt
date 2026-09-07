package com.jobmaker.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Apercu du document tel qu'il sera imprime.
 *
 * C'est le meme moteur (WebView) qui produit le PDF : ce que l'ecran montre est
 * donc exactement ce que le recruteur recevra, a la pagination pres.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ApercuHtml(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setInitialScale(1)
                isVerticalScrollBarEnabled = true
            }
        },
        update = { vue ->
            // On ne recharge que si le contenu a reellement change : recharger a
            // chaque recomposition ferait sauter le zoom et le defilement.
            if (vue.tag != html.hashCode()) {
                vue.tag = html.hashCode()
                vue.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
    )
}
