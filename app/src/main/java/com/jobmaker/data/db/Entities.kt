package com.jobmaker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Le profil tient dans une seule ligne : il n'y a qu'un utilisateur, et le
 * contenu part de toute facon vers le modele sous forme de JSON.
 */
@Entity(tableName = "profil")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val json: String,
    val modifieLe: Long = System.currentTimeMillis(),
) {
    companion object { const val SINGLETON_ID = 1 }
}

/**
 * Un dossier de candidature. Les colonnes hors [json] sont dupliquees
 * uniquement pour pouvoir lister et trier sans deserialiser chaque ligne.
 */
@Entity(tableName = "candidatures")
data class CandidatureEntity(
    @PrimaryKey val id: String,
    val json: String,
    val poste: String,
    val entreprise: String,
    val statut: String,
    val scoreAts: Int,
    val creeLe: Long,
    val modifieLe: Long,
)

@Entity(tableName = "explications")
data class ExplanationEntity(
    @PrimaryKey val id: String,
    val offreTexte: String,
    val json: String,
    val intitule: String,
    val creeLe: Long,
)
