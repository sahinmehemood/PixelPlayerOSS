package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class SongInfoBottomSheetViewModelTest {

    private val appContext = mockk<Context>(relaxed = true)
    private val viewModel = SongInfoBottomSheetViewModel(
        musicDao = mockk(relaxed = true),
        musicBrainzRepository = mockk<MusicBrainzRepository>(relaxed = true),
        appContext = appContext,
    )

    @Test
    fun `local song shows its file path under the localized path label`() {
        val info = viewModel.getSongLocationInfo(
            song(
                path = "/storage/emulated/0/Music/song.flac",
                contentUri = "content://media/external/audio/media/1",
            ),
        )

        assertThat(info.labelRes).isEqualTo(R.string.song_info_label_path)
        assertThat(info.value).isEqualTo("/storage/emulated/0/Music/song.flac")
        assertThat(info.isRemote).isFalse()
    }

    @Test
    fun `telegram song shows the Telegram source under the provider label`() {
        every { appContext.getString(R.string.telegram_music_source) } returns "Telegram"

        val info = viewModel.getSongLocationInfo(
            song(path = "", contentUri = "telegram:-100123:456"),
        )

        assertThat(info.labelRes).isEqualTo(R.string.song_info_label_provider)
        assertThat(info.value).isEqualTo("Telegram")
        assertThat(info.isRemote).isTrue()
    }

    private fun song(path: String, contentUri: String) = Song(
        id = "1",
        title = "Song",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = path,
        contentUriString = contentUri,
        albumArtUriString = null,
        duration = 60_000L,
        mimeType = "audio/flac",
        bitrate = null,
        sampleRate = null,
    )
}
