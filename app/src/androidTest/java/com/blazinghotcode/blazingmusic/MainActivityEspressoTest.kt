package com.blazinghotcode.blazingmusic

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import org.hamcrest.Matchers.allOf
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
        onView(withId(R.id.navPlaylists)).check(matches(isDisplayed()))
    }

    @Test
    fun canSwitchTabsWithoutCrash() {
        onView(withId(R.id.navPlaylists)).perform(click())
        onView(withId(R.id.playlistContainer)).check(matches(isDisplayed()))

        onView(withId(R.id.navHome)).perform(click())
        onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
    }

    @Test
    fun playlistsRootControlsAreVisible() {
        onView(withId(R.id.navPlaylists)).perform(click())
        onView(withId(R.id.btnCreatePlaylist)).check(matches(isDisplayed()))
        onView(withId(R.id.etSearchPlaylists)).check(matches(isDisplayed()))
        onView(withId(R.id.rvPlaylists)).check(matches(isDisplayed()))
    }

    @Test
    fun createPlaylistDialogOpensAndCloses() {
        onView(withId(R.id.navPlaylists)).perform(click())
        onView(withId(R.id.btnCreatePlaylist)).perform(click())
        onView(withHint("Playlist name")).check(matches(isDisplayed()))
        onView(allOf(withText("Cancel"), isDisplayed())).perform(click())
        onView(withHint("Playlist name")).check(doesNotExist())
    }

    @Test
    fun sortButtonWorksInHomeWithoutCrash() {
        onView(withId(R.id.navHome)).perform(click())
        if (isViewActuallyDisplayed(R.id.homeStateContainer)) {
            // When permission/state panel is shown, Home controls are intentionally hidden.
            return
        }
        onView(withId(R.id.btnSortSongs)).perform(click())
        onView(withText("Sort songs")).check(matches(isDisplayed()))
        onView(withText("Close")).perform(click())
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
}
