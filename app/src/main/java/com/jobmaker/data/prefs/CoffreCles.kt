package com.jobmaker.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jobmaker.llm.cloud.FournisseurCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage des cles d'API, chiffre par le materiel du telephone.
 *
 * Pourquoi ne pas simplement les mettre dans les preferences : une cle d'API
 * gratuite reste une cle. Posee en clair, elle est lisible par quiconque obtient
 * une sauvegarde de l'appareil ou un acces root, et peut servir a consommer le
 * quota -- voire, chez certains fournisseurs, a lire l'historique des requetes,
 * c'est-a-dire des CV.
 *
 * Ce que fait ce coffre :
 *  - la cle de chiffrement est generee DANS le Keystore Android, donc dans
 *    l'element securise du telephone. Elle ne peut pas en sortir : meme
 *    l'application ne peut que demander au systeme de chiffrer ou dechiffrer.
 *  - seul le resultat chiffre (AES-256-GCM, vecteur d'initialisation en tete)
 *    est ecrit sur le disque, dans un fichier a part, exclu des sauvegardes.
 *  - une restauration sur un autre telephone ne rend rien : la cle materielle
 *    n'a pas suivi. L'utilisateur ressaisit sa cle d'API, et c'est voulu.
 *
 * Ce que ce coffre ne pretend pas faire : resister a un appareil deverrouille
 * et compromis pendant que l'application tourne. Aucun stockage local ne le
 * peut.
 */
class CoffreCles(context: Context) {

    private val appContext = context.applicationContext

    /** Cle en clair pour [fournisseur], ou null si aucune n'est enregistree. */
    suspend fun cle(fournisseur: FournisseurCloud): String? {
        val stocke = appContext.coffreStore.data.first()[cleStockage(fournisseur)]
        if (stocke.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) { dechiffrer(stocke) } ?: run {
            // Chiffre illisible : materiel remplace, sauvegarde restauree, ou
            // Keystore reinitialise. On nettoie pour eviter de le retenter a
            // chaque generation.
            Log.w(TAG, "Cle illisible pour ${fournisseur.name}, entree effacee")
            effacer(fournisseur)
            null
        }
    }

    suspend fun enregistrer(fournisseur: FournisseurCloud, valeur: String) {
        val propre = valeur.trim()
        if (propre.isEmpty()) {
            effacer(fournisseur)
            return
        }
        val chiffre = withContext(Dispatchers.IO) { chiffrer(propre) }
        appContext.coffreStore.edit { it[cleStockage(fournisseur)] = chiffre }
    }

    suspend fun effacer(fournisseur: FournisseurCloud) {
        appContext.coffreStore.edit { it.remove(cleStockage(fournisseur)) }
    }

    /**
     * Empreinte affichable de chaque cle enregistree : de quoi verifier d'un
     * coup d'oeil laquelle est en place, sans jamais la remontrer en entier.
     */
    val empreintes: Flow<Map<FournisseurCloud, String>> =
        appContext.coffreStore.data.map { prefs ->
            FournisseurCloud.entries.mapNotNull { f ->
                val chiffre = prefs[cleStockage(f)] ?: return@mapNotNull null
                val claire = dechiffrer(chiffre) ?: return@mapNotNull null
                f to masquer(claire)
            }.toMap()
        }.flowOn(Dispatchers.Default)

    // -----------------------------------------------------------------------
    // Chiffrement
    // -----------------------------------------------------------------------

    private fun chiffrer(clair: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, cleMaitresse())
        val iv = cipher.iv
        val chiffre = cipher.doFinal(clair.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + chiffre, Base64.NO_WRAP)
    }

    private fun dechiffrer(stocke: String): String? = runCatching {
        val octets = Base64.decode(stocke, Base64.NO_WRAP)
        require(octets.size > TAILLE_IV) { "bloc trop court" }
        val iv = octets.copyOfRange(0, TAILLE_IV)
        val corps = octets.copyOfRange(TAILLE_IV, octets.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, cleMaitresse(), GCMParameterSpec(TAILLE_TAG, iv))
        String(cipher.doFinal(corps), Charsets.UTF_8)
    }.getOrNull()

    /** Recupere la cle materielle, ou la cree au premier usage. */
    private fun cleMaitresse(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val specs = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Pas d'authentification exigee a chaque usage : une generation peut
            // tourner en arriere-plan, telephone dans la poche.
            .setUserAuthenticationRequired(false)

        // La puce dediee quand elle existe et accepte l'AES ; sinon le TEE, qui
        // reste de toute facon hors de portee du systeme de fichiers.
        runCatching {
            generateur.init(specs.setIsStrongBoxBacked(true).build())
            return generateur.generateKey()
        }
        generateur.init(specs.setIsStrongBoxBacked(false).build())
        return generateur.generateKey()
    }

    private fun cleStockage(fournisseur: FournisseurCloud) =
        stringPreferencesKey("cle_${fournisseur.name.lowercase()}")

    companion object {
        private const val TAG = "CoffreCles"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "jobmaker_coffre_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAILLE_IV = 12
        private const val TAILLE_TAG = 128

        /** "gsk_abcdef...WXYZ" devient "gsk_...WXYZ". */
        fun masquer(cle: String): String {
            val propre = cle.trim()
            if (propre.length <= 8) return "****"
            return propre.take(4) + "..." + propre.takeLast(4)
        }
    }
}

/**
 * Fichier distinct des reglages : il ne contient que des secrets, ce qui permet
 * de l'exclure des sauvegardes sans exclure les preferences de l'application.
 */
private val Context.coffreStore by preferencesDataStore(name = "jobmaker_secrets")
