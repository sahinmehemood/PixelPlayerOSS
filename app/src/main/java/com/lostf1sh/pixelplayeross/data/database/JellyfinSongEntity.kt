package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jellyfin_songs",
    indices = [
        Index(value = ["jellyfin_id"]),
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "date_added"])
    ]
)
data class JellyfinSongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "jellyfin_id") val jellyfinId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val duration: Long,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "disc_number") val discNumber: Int,
    val year: Int,
    val genre: String?,
    val bitRate: Int?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val path: String,
    @ColumnInfo(name = "date_added") val dateAdded: Long
)
