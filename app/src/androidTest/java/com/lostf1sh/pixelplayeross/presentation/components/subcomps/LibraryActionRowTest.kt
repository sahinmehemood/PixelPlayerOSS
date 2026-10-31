package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library toolbar switches between its Shuffle/New and breadcrumb modes, shows a Home icon at
 * the folder root instead of a dead back arrow.
 */
@RunWith(AndroidJUnit4::class)
class LibraryActionRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(@StringRes id: Int): String = composeRule.activity.getString(id)

    @Test
    fun folderRootShowsDisabledHomeButton() {
        composeRule.setContent {
            MaterialTheme {
                LibraryActionRow(
                    onMainActionClick = {},
                    iconRotation = 0f,
                    onSortClick = {},
                    showSortButton = true,
                    isPlaylistTab = false,
                    isFoldersTab = true,
                    currentFolder = null,
                    folderRootPath = ROOT_PATH,
                    folderRootLabel = ROOT_LABEL,
                    onFolderClick = {},
                    onNavigateBack = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.tab_home))
            .assertExists()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.auth_cd_back))
            .assertDoesNotExist()
    }

    @Test
    fun subfolderShowsEnabledBackButtonThatNavigatesBack() {
        var backClicks = 0
        composeRule.setContent {
            MaterialTheme {
                LibraryActionRow(
                    onMainActionClick = {},
                    iconRotation = 0f,
                    onSortClick = {},
                    showSortButton = true,
                    isPlaylistTab = false,
                    isFoldersTab = true,
                    currentFolder = MusicFolder(path = "$ROOT_PATH/Music", name = "Music"),
                    folderRootPath = ROOT_PATH,
                    folderRootLabel = ROOT_LABEL,
                    onFolderClick = {},
                    onNavigateBack = { backClicks++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.tab_home))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.auth_cd_back))
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertThat(backClicks).isEqualTo(1)
        }
    }

    @Test
    fun switchingToFoldersSwapsShuffleForBreadcrumbsAndBack() {
        var isFoldersTab by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                LibraryActionRow(
                    onMainActionClick = {},
                    iconRotation = 0f,
                    onSortClick = {},
                    showSortButton = true,
                    isPlaylistTab = false,
                    isFoldersTab = isFoldersTab,
                    currentFolder = null,
                    folderRootPath = ROOT_PATH,
                    folderRootLabel = ROOT_LABEL,
                    onFolderClick = {},
                    onNavigateBack = {}
                )
            }
        }

        val shuffle = string(R.string.cd_shuffle_play)
        composeRule.onNodeWithContentDescription(shuffle).assertExists()
        composeRule.onNodeWithText(ROOT_LABEL).assertDoesNotExist()

        isFoldersTab = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ROOT_LABEL).assertExists()
        composeRule.onNodeWithContentDescription(shuffle).assertDoesNotExist()

        isFoldersTab = false
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(shuffle).assertExists()
        composeRule.onNodeWithText(ROOT_LABEL).assertDoesNotExist()
    }

    private companion object {
        const val ROOT_PATH = "/storage/emulated/0"
        const val ROOT_LABEL = "Device storage"
    }
}
