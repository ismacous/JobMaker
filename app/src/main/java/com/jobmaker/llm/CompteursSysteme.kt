package com.jobmaker.llm

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Instantane des compteurs que le noyau tient pour le processus.
 *
 * Sert a repondre a une seule question, impossible a trancher autrement : quand
 * la generation est lente, est-ce que le telephone calcule ou est-ce qu'il
 * attend ?
 *
 *  - [msProcesseur] additionne le temps passe sur un coeur par tous les threads.
 *    Avec 6 threads qui calculent vraiment, il doit valoir environ six fois le
 *    temps ecoule. S'il vaut moins que le temps ecoule, le moteur attend.
 *  - [defautsMajeurs] compte les pages qu'il a fallu aller relire sur le
 *    stockage. Un modele mappe et evince par Android se voit ici : des
 *    centaines de milliers de defauts pendant une seule generation.
 *  - [octetsLus] compte les octets reellement lus sur le stockage. Un modele
 *    de 2,5 Go relu en boucle se voit ici en gigaoctets.
 */
data class CompteursSysteme(
    val msProcesseur: Long,
    val defautsMineurs: Long,
    val defautsMajeurs: Long,
    val octetsLus: Long,
) {
    operator fun minus(debut: CompteursSysteme) = CompteursSysteme(
        msProcesseur = msProcesseur - debut.msProcesseur,
        defautsMineurs = defautsMineurs - debut.defautsMineurs,
        defautsMajeurs = defautsMajeurs - debut.defautsMajeurs,
        octetsLus = octetsLus - debut.octetsLus,
    )

    companion object {
        private val ticksParSeconde: Long =
            runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()
                ?.takeIf { it > 0 } ?: 100L

        fun lire(): CompteursSysteme {
            var msProc = 0L
            var mineurs = 0L
            var majeurs = 0L
            runCatching {
                val stat = File("/proc/self/stat").readText()
                // Le nom du processus, champ 2, est entre parentheses et peut
                // contenir des espaces : on repart d'apres la derniere.
                val apres = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ")
                // apres[0] est le champ 3 : champ N se lit donc en apres[N - 3].
                mineurs = apres.getOrNull(7)?.toLongOrNull() ?: 0L
                majeurs = apres.getOrNull(9)?.toLongOrNull() ?: 0L
                val utime = apres.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = apres.getOrNull(12)?.toLongOrNull() ?: 0L
                msProc = (utime + stime) * 1000L / ticksParSeconde
            }
            // /proc/self/io n'est pas lisible sur toutes les versions d'Android.
            val octets = runCatching {
                File("/proc/self/io").readLines()
                    .firstOrNull { it.startsWith("read_bytes:") }
                    ?.substringAfter(':')?.trim()?.toLongOrNull() ?: -1L
            }.getOrDefault(-1L)

            return CompteursSysteme(msProc, mineurs, majeurs, octets)
        }
    }
}
