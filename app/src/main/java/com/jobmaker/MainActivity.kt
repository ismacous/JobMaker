package com.jobmaker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.jobmaker.ui.JobMakerApplication

class MainActivity : ComponentActivity() {

    /**
     * Texte recu depuis le partage d'une autre application (Indeed, navigateur).
     * Permet de partager une offre directement vers JobMaker au lieu de faire
     * un copier-coller.
     */
    private val textePartage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lireIntent(intent)

        val container = (application as JobMakerApp).container

        setContent {
            JobMakerApplication(
                container = container,
                offrePartagee = textePartage.value,
                onOffrePartageeConsommee = { textePartage.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lireIntent(intent)
    }

    private fun lireIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                textePartage.value = it
            }
        }
    }
}
