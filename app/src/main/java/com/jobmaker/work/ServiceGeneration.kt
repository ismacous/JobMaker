package com.jobmaker.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jobmaker.JobMakerApp
import com.jobmaker.MainActivity
import com.jobmaker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Maintient le processus vivant pendant une generation.
 *
 * Sans ce service, Android gele l'application des qu'elle passe en
 * arriere-plan : il fallait rester devant l'ecran pendant toute la redaction.
 * Le service ne calcule rien lui-meme -- c'est [MoteurCandidature] qui tient
 * le travail -- il ne fait que declarer a Android que quelque chose
 * d'important est en cours, et le montrer dans une notification.
 */
class ServiceGeneration : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var suivi: Job? = null

    /**
     * Android tue l'application si un service demarre par
     * startForegroundService n'appelle pas startForeground. Il faut donc le
     * faire meme sur le chemin d'arret, si l'ordre des intents fait qu'on
     * n'est jamais passe au premier plan.
     */
    private var auPremierPlan = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        creerCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARRET) {
            if (!auPremierPlan) demarrerAuPremierPlan(notification(EtatGeneration()))
            arreterProprement()
            return START_NOT_STICKY
        }

        demarrerAuPremierPlan(notification(EtatGeneration(enCours = true,
            etapeTitre = "Preparation...")))

        val moteur = (application as JobMakerApp).container.moteurCandidature
        suivi?.cancel()
        suivi = scope.launch {
            var derniereMaj = 0L
            moteur.etat.collectLatest { etat ->
                if (!etat.enCours) {
                    annoncerLaFin(etat)
                    arreterProprement()
                    return@collectLatest
                }
                // L'etat change a chaque remontee de texte. Redessiner la
                // notification a ce rythme envoie une transaction au serveur
                // systeme plusieurs fois par seconde, sur le fil principal --
                // et Android en jette la plupart au-dela de cinq par seconde.
                // Le travail est paye sans que rien ne s'affiche, pendant que
                // les threads de calcul, qui se synchronisent a chaque couche
                // du modele, paient chaque preemption. Une mise a jour par
                // seconde suffit largement a une barre d'avancement.
                val maintenant = System.currentTimeMillis()
                if (maintenant - derniereMaj < MS_ENTRE_NOTIFICATIONS) return@collectLatest
                derniereMaj = maintenant
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(ID_NOTIF, notification(etat))
            }
        }
        // Pas de START_STICKY : une generation relancee toute seule apres que
        // le systeme a tue l'application ne rendrait service a personne.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        suivi?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun arreterProprement() {
        suivi?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        auPremierPlan = false
        stopSelf()
    }

    private fun demarrerAuPremierPlan(notif: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_NOTIF, notif)
        }
        auPremierPlan = true
    }

    private fun notification(etat: EtatGeneration): android.app.Notification {
        val ouvrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val detail = buildString {
            append("Etape ${etat.etapeIndex.coerceAtLeast(1)}/${etat.etapesTotal}")
            append(" - ").append(etat.phase.libelle)
            if (etat.phase == PhaseGeneration.LECTURE && etat.promptTotal > 0) {
                append(" ${etat.promptLus}/${etat.promptTotal}")
            }
            if (etat.phase == PhaseGeneration.REDACTION && etat.tokensEcrits > 0) {
                append(" ${etat.tokensEcrits} mots")
            }
        }

        return NotificationCompat.Builder(this, CANAL)
            .setContentTitle(etat.etapeTitre.ifBlank { "Generation en cours" })
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(etat.etapesTotal.coerceAtLeast(1), etat.etapeIndex, false)
            .setContentIntent(ouvrir)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Notification de fin. Sans elle, quitter l'application pendant les dix
     * minutes de redaction reviendrait a devoir y revenir au hasard pour voir
     * si c'est termine.
     */
    private fun annoncerLaFin(etat: EtatGeneration) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val (titre, texte) = when {
            etat.candidatureId != null ->
                "CV et lettre prets" to "Touchez pour les relire avant d'envoyer."
            etat.erreur != null ->
                "La generation a echoue" to etat.erreur
            // Interruption demandee par l'utilisateur : il est devant l'ecran,
            // le prevenir n'apporterait rien.
            else -> return
        }
        val ouvrir = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            ID_NOTIF_FIN,
            NotificationCompat.Builder(this, CANAL_FIN)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(ouvrir)
                .build(),
        )
    }

    private fun creerCanal() {
        // createNotificationChannel est idempotent : le redeclarer a chaque
        // demarrage garantit qu'un canal ajoute par une mise a jour existe.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CANAL, "Generation en cours", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Avancement de la redaction du CV et de la lettre."
                    setShowBadge(false)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CANAL_FIN, "Candidature prete", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Vous previent quand le CV et la lettre sont ecrits."
            }
        )
    }

    companion object {
        private const val CANAL = "generation"
        private const val CANAL_FIN = "generation_fin"
        private const val ID_NOTIF = 1001
        private const val ID_NOTIF_FIN = 1002
        private const val ACTION_ARRET = "com.jobmaker.ARRET_GENERATION"

        /** Intervalle minimal entre deux redessins de la notification. */
        private const val MS_ENTRE_NOTIFICATIONS = 1000L

        fun demarrer(context: Context) {
            val intent = Intent(context, ServiceGeneration::class.java)
            context.startForegroundService(intent)
        }

        fun arreter(context: Context) {
            val intent = Intent(context, ServiceGeneration::class.java)
                .setAction(ACTION_ARRET)
            // Le service peut deja s'etre arrete tout seul en voyant l'etat
            // passer a "termine" : dans ce cas il n'y a rien a faire.
            runCatching { context.startService(intent) }
        }
    }
}
