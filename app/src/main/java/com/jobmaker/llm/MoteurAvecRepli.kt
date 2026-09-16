package com.jobmaker.llm

import android.util.Log
import com.jobmaker.llm.cloud.PanneCloud

/**
 * Enveloppe un moteur distant d'un filet de securite local.
 *
 * Le cas vise est banal : on lance une candidature dans le metro, le reseau
 * tombe a la station suivante, ou le quota gratuit du jour vient d'etre epuise.
 * Sans filet, tout est perdu et il faut recommencer. Avec, l'etape en cours
 * repart sur le modele du telephone et la candidature aboutit -- plus lentement,
 * en le disant clairement.
 *
 * La bascule ne se declenche que sur une panne passagere ([PanneCloud.repliPossible]).
 * Une cle invalide ou un modele inexistant continuent de remonter a
 * l'utilisateur : masquer une erreur de reglage derriere un repli silencieux le
 * laisserait croire que tout va bien pendant que son quota gratuit dort.
 */
class MoteurAvecRepli(
    private val principal: MoteurTexte,
    private val secours: MoteurTexte,
    private val onBascule: (String) -> Unit,
) : MoteurTexte {

    @Volatile
    private var bascule = false

    private var dernierRole: AgentRole = AgentRole.ANALYSIS

    private val actuel: MoteurTexte get() = if (bascule) secours else principal

    override val nomCourt: String get() = actuel.nomCourt

    override val distant: Boolean get() = actuel.distant

    override suspend fun preparer(role: AgentRole): MoteurPret {
        dernierRole = role
        return try {
            actuel.preparer(role)
        } catch (e: PanneCloud) {
            if (bascule || !e.repliPossible) throw e
            basculer(e)
        }
    }

    override suspend fun tokenCount(texte: String): Int = actuel.tokenCount(texte)

    override suspend fun completer(
        messages: List<ChatMessage>,
        params: GenerationParams,
        onLecturePrompt: ((Int, Int, Long) -> Unit)?,
        onToken: ((String) -> Unit)?,
    ): String = try {
        actuel.completer(messages, params, onLecturePrompt, onToken)
    } catch (e: PanneCloud) {
        if (bascule || !e.repliPossible) throw e
        basculer(e)
        // On rejoue l'etape entiere : une reponse coupee en plein milieu n'est
        // pas rattrapable, et le modele local repart de la meme consigne.
        secours.completer(messages, params, onLecturePrompt, onToken)
    }

    override suspend fun liberer() {
        runCatching { principal.liberer() }
        runCatching { secours.liberer() }
    }

    private suspend fun basculer(cause: PanneCloud): MoteurPret {
        Log.w(TAG, "Bascule sur le moteur local : ${cause.message}")
        bascule = true
        val pret = secours.preparer(dernierRole)
        onBascule(
            "${cause.message} Le modele du telephone (${pret.nomModele}) prend le relais " +
                "pour la suite : c'est plus lent, mais la candidature va au bout."
        )
        return pret
    }

    private companion object { const val TAG = "MoteurAvecRepli" }
}
