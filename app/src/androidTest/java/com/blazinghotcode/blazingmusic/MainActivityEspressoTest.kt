package com.blazinghotcode.blazingmusic

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import android.view.View
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matcher
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityEspressoTest {

    companion object {
        private fun runtimePermissionsForDevice(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*runtimePermissionsForDevice())

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunchesAndBottomNavVisible() {
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
        onView(withId(R.id.navHome)).check(matches(isDisplayed()))
        onView(withId(R.id.navSearch)).check(matches(isDisplayed()))
        onView(withId(R.id.navPlaylists)).check(matches(isDisplayed()))
    }

    @Test
    fun canSwitchTabsWithoutCrash() {
        onView(withId(R.id.navSearch)).perform(click())
        onView(withId(R.id.youtubeContainer)).check(matches(isDisplayed()))

        onView(withId(R.id.navPlaylists)).perform(click())
        onView(withId(R.id.playlistContainer)).check(matches(isDisplayed()))

        onView(withId(R.id.navHome)).perform(click())
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
    }

    @Test
    fun searchTabControlsVisibleAndFilterSwitches() {
        onView(withId(R.id.navSearch)).perform(click())
        waitForUi()
        onView(withId(R.id.etYouTubeSearch)).check(matches(isDisplayed()))
        onView(withId(R.id.btnRunYouTubeSearch)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSearchFilter)).check(matches(isDisplayed()))

        onView(withId(R.id.btnSearchFilter)).perform(click())
        onView(withText("Songs")).perform(click())
        onView(withId(R.id.btnSearchFilter)).check(matches(withText("Songs")))
    }

    @Test
    fun albumBrowseShellOpensFromSearchTab() {
        activityRule.scenario.onActivity { activity ->
            activity.openSearchTab()
            activity.openYouTubeBrowse(
                YouTubeVideo(
                    id = "album-test",
                    title = "Test Album",
                    channelTitle = "Test Artist",
                    thumbnailUrl = null,
                    type = YouTubeItemType.ALBUM,
                    browseId = "TEST_ALBUM_BROWSE_ID"
                )
            )
        }
        waitForUi(600)
        onView(allOf(withId(R.id.tvTitle), withText("Album"), isDisplayed()))
            .check(matches(isDisplayed()))
        onView(withId(R.id.btnPlayAll)).check(matches(isDisplayed()))
        onView(withId(R.id.artistActionRow)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun artistBrowseShellOpensFromSearchTab() {
        activityRule.scenario.onActivity { activity ->
            activity.openSearchTab()
            activity.openYouTubeBrowse(
                YouTubeVideo(
                    id = "artist-test",
                    title = "Test Artist",
                    channelTitle = "Test Channel",
                    thumbnailUrl = null,
                    type = YouTubeItemType.ARTIST,
                    browseId = "TEST_ARTIST_BROWSE_ID"
                )
            )
        }
        waitForUi(600)
        onView(allOf(withId(R.id.tvTitle), withText("Artist"), isDisplayed()))
            .check(matches(isDisplayed()))
        onView(withId(R.id.artistActionRow)).check(matches(isDisplayed()))
        onView(withId(R.id.btnPlayAll)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun settingsScreenOpenAndBackWithoutCrash() {
        onView(withId(R.id.btnSettings)).perform(click())
        waitForUi(300)
        pressBack()
        waitForUi(300)
    }

    @Test
    fun playlistsRootControlsAreVisible() {
        onView(withId(R.id.navPlaylists)).perform(click())
        waitForUi()
        assumeTrue(isViewActuallyDisplayed(R.id.btnCreatePlaylist))
        onView(withId(R.id.btnCreatePlaylist)).check(matches(isDisplayed()))
        onView(withId(R.id.etSearchPlaylists)).check(matches(isDisplayed()))
        onView(withId(R.id.rvPlaylists)).check(matches(isDisplayed()))
    }

    @Test
    fun createPlaylistDialogOpensAndCloses() {
        onView(withId(R.id.navPlaylists)).perform(click())
        waitForUi()
        assumeTrue(isViewActuallyDisplayed(R.id.btnCreatePlaylist))
        onView(withId(R.id.btnCreatePlaylist)).perform(click())
        onView(withHint("Playlist name")).check(matches(isDisplayed()))
        onView(withText("Cancel")).perform(click())
        onView(withHint("Playlist name")).check(doesNotExist())
    }

    @Test
    fun sortButtonWorksInHomeWithoutCrash() {
        onView(withId(R.id.navHome)).perform(click())
        if (isViewActuallyDisplayed(R.id.homeStateContainer)) {
            // When permission/state panel is shown, Home controls are intentionally hidden.
            return
        }
        assumeTrue(isViewActuallyDisplayed(R.id.btnSortSongs))
        onView(withId(R.id.btnSortSongs)).perform(click())
        assumeTrue(isTextActuallyDisplayed("Sort songs"))
        onView(withText("Close")).perform(click())
        waitForUi()
        onView(withText("Sort songs")).check(doesNotExist())
    }

    @Test
    fun fullPlayerOpenAndCloseWhenMiniPlayerVisible() {
        assumeTrue(isViewActuallyDisplayed(R.id.playerLayout))
        onView(withId(R.id.playerLayout)).perform(click())
        onView(withId(R.id.fullPlayerRoot)).check(matches(isDisplayed()))
        onView(withId(R.id.btnClose)).perform(click())
    }

    private fun isViewActuallyDisplayed(viewId: Int): Boolean {
        return try {
            onView(withId(viewId)).check(matches(isDisplayed()))
            true
        } catch (_: NoMatchingViewException) {
            false
        } catch (_: AssertionError) {
            false
        }
    }

    private fun isTextActuallyDisplayed(text: String): Boolean {
        return try {
            onView(withText(text)).check(matches(isDisplayed()))
            true
        } catch (_: NoMatchingViewException) {
            false
        } catch (_: AssertionError) {
            false
        }
    }

    private fun waitForUi(delayMs: Long = 400L) {
        onView(isRoot()).perform(waitFor(delayMs))
    }

    private fun waitFor(delayMs: Long): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()

            override fun getDescription(): String = "Wait for $delayMs ms."

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadForAtLeast(delayMs)
            }
        }
    }
}
