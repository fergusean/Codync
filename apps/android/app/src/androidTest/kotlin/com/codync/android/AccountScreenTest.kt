package com.codync.android

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.codync.android.core.*
import com.codync.android.design.CodyncTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class AccountScreenTest {
    @get:Rule val compose = createComposeRule()
    private val first = SavedAccount("first", "first-session", "alex@example.test", null)
    private val second = SavedAccount("second", "second-session", "sam@example.test", null)
    private val account = AccountState(ready = true, configured = true, userId = first.userId,
        accounts = listOf(first, second), multiple = true)
    private fun fixture(accountState: AccountState = account) = AppState(loading = false,
        setupComplete = true, account = accountState, contextId = "profile-fixture")

    @Before fun isolatedEmulatorOnly() { assumeTrue(Build.PRODUCT.startsWith("sdk")) }

    @Test fun signedInOverviewKeepsTheProfileAboveItsNavigationAndHidesComputerSettings() {
        var scanned = false
        var closed = false
        compose.setContent { CodyncTheme { AccountScreenContent(fixture(), AccountScreenActions(),
            { closed = true }, scan = { scanned = true }) } }
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to account").assertDoesNotExist()
        val scan = compose.onNodeWithText("Scan a computer's code").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithText(first.email).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val computers = compose.onNodeWithText("Computers").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(scan.bottom < identity.top)
        assertTrue(identity.bottom < computers.top)
        compose.onNodeWithContentDescription(first.email).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithContentDescription(second.email).assertIsNotSelected().assertIsEnabled()
        compose.onNodeWithContentDescription("Add account").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithText("Start over").assertDoesNotExist()
        snapshot("signed-in")
        compose.onNodeWithText("Scan a computer's code").performClick()
        assertTrue(scanned)
        compose.onNodeWithContentDescription("Close account").performClick()
        assertTrue(closed)
    }

    @Test fun accountFacesSwitchAndTheAddButtonRevealsProvidersOnlyOnDemand() {
        val state = mutableStateOf(fixture())
        val requests = mutableListOf<Pair<Boolean, Boolean>>()
        var switched: SavedAccount? = null
        compose.setContent { CodyncTheme { AccountScreenContent(state.value, AccountScreenActions(
            switchAccount = { switched = it; state.value = state.value.copy(account = account.copy(userId = it.userId)) },
            signIn = { apple, hosted -> requests.add(apple to hosted) }), {}) } }
        compose.onNodeWithContentDescription("Add account").performClick()
        compose.onNodeWithText("Continue with Apple").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").performScrollTo().performClick()
        assertEquals(listOf(false to false), requests)
        compose.onNodeWithContentDescription(second.email).performScrollTo().performClick()
        assertEquals(second, switched)
        compose.onNodeWithText(second.email).assertIsDisplayed()
        compose.onNodeWithContentDescription(second.email).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
        compose.onNodeWithText("Continue with Apple").assertDoesNotExist()
        state.value = state.value.copy(busy = setOf("session"))
        compose.onNodeWithContentDescription(first.email).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Add account").assertIsNotEnabled()
        compose.onNodeWithText("Sign out").assertIsNotEnabled()
    }

    @Test fun signedOutProvidersAndUnconfiguredPairingRemainAvailable() {
        val state = mutableStateOf(fixture(AccountState(ready = true, configured = true)))
        val requests = mutableListOf<Pair<Boolean, Boolean>>()
        var retries = 0
        compose.setContent { CodyncTheme { AccountScreenContent(state.value, AccountScreenActions(
            signIn = { apple, hosted -> requests.add(apple to hosted) }, retryAccounts = { retries++ }), {}) } }
        val apple = compose.onNodeWithText("Continue with Apple").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val google = compose.onNodeWithText("Continue with Google").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(apple.bottom <= google.top)
        assertEquals(apple.left, google.left, 1f)
        assertEquals(apple.right, google.right, 1f)
        compose.onNodeWithText("Continue with Apple").performClick()
        compose.onNodeWithText("Continue with Google").performClick()
        compose.onNodeWithText("Other sign-in options").performScrollTo().performClick()
        assertEquals(listOf(true to false, false to false, false to true), requests)
        state.value = state.value.copy(account = state.value.account.copy(ready = false, error = "Couldn't restore sign-in."))
        compose.onNodeWithText("Continue with Apple").assertIsNotEnabled()
        compose.onNodeWithText("Continue with Google").assertIsNotEnabled()
        compose.onNodeWithText("Retry sign-in").performScrollTo().performClick()
        assertEquals(1, retries)
        state.value = state.value.copy(account = AccountState(ready = true, configured = false))
        compose.onNodeWithText("QR pairing only in this build").assertIsDisplayed()
        compose.onNodeWithText("Continue with Apple").assertDoesNotExist()
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
        compose.onNodeWithText("Scan a computer's code").assertIsDisplayed()
        compose.onNodeWithText("Computers").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        snapshot("pairing-only")
    }

    @Test fun signOutRequiresItsDestructiveConfirmationAndCancelPreservesTheAccount() {
        var signOuts = 0
        compose.setContent { CodyncTheme { AccountScreenContent(fixture(), AccountScreenActions(signOut = { signOuts++ }), {}) } }
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out of ${first.email}?").assertIsDisplayed()
        compose.onNodeWithText("This phone forgets the account's computers. Your bots stay on them.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close account").assertDoesNotExist()
        assertEquals(0, signOuts)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Sign out of ${first.email}?").assertDoesNotExist()
        assertEquals(0, signOuts)
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(1, signOuts)
        compose.onNodeWithText(first.email).assertIsDisplayed()
    }

    @Test fun computerSettingsKeepRoutesGrantsNotificationsAndResetBehindTheComputersRow() {
        val cloud = CloudComputer("paired", "Fixture computer", "fixture-key", online = true)
        val device = CloudDevice("device", "device-key", "Other fixture phone", platform = "android")
        val grant = CloudGrant("grant", "granted-device", "Granted fixture phone")
        val state = mutableStateOf(fixture().copy(
            computers = listOf(Computer(cloud.computerId, cloud.name, cloud.signKey)), cloudComputers = listOf(cloud),
            devices = listOf(device), grants = mapOf(cloud.computerId to listOf(grant)),
            connection = LinkState.Ready(HostRoute.Direct)))
        var route: ConnectionRoute? = null
        var grantsRequested: String? = null
        var revoked: Pair<String, String>? = null
        var forgotten = 0
        var reset = 0
        var paired = 0
        val actions = AccountScreenActions(setRoute = { route = it }, loadGrants = { grantsRequested = it },
            revokeGrant = { id, grantId -> revoked = id to grantId }, forgetComputer = { forgotten++ },
            notifications = { state.value = state.value.copy(notifications = it) }, startOver = { reset++ })
        compose.setContent { CodyncTheme { AccountScreenContent(state.value, actions, {}, pair = { paired++ }) } }
        compose.onNodeWithText("Computers").performClick()
        compose.onNodeWithText("Computers & settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to account").assertIsDisplayed()
        compose.onAllNodesWithText(cloud.name).assertCountEquals(1)
        compose.onNodeWithContentDescription("Connection").performClick()
        compose.onNodeWithText("Wi-Fi or Tailscale only, never Cloudflare").performClick()
        assertEquals(ConnectionRoute.DirectOnly, route)
        compose.onNodeWithText("Authorized devices").performClick()
        assertEquals(cloud.computerId, grantsRequested)
        compose.onNodeWithText(grant.deviceName!!).assertIsDisplayed()
        compose.onNodeWithContentDescription("Revoke ${grant.deviceName}'s access to ${cloud.name}").performClick()
        compose.onNodeWithText("Revoke ${grant.deviceName}'s access?").assertIsDisplayed()
        assertNull(revoked)
        compose.onNodeWithText("Revoke").performClick()
        assertEquals(cloud.computerId to grant.grantId, revoked)
        compose.onNodeWithContentDescription("Connection").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, forgotten)
        compose.onNodeWithText("Pair a computer").performScrollTo().performClick()
        assertEquals(1, paired)
        compose.onNodeWithText("Push notifications").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Android notification settings").assertIsDisplayed()
        snapshot("computers")
        compose.onNodeWithText("Start over").performScrollTo().performClick()
        assertEquals(0, reset)
        compose.onNodeWithText("Start over").performClick()
        assertEquals(1, reset)
        compose.onNodeWithContentDescription("Back to account").performClick()
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection").assertDoesNotExist()
    }

    @Test fun aPendingAccessRequestShowsItsCodeAndCanBeCancelled() {
        var cancelled = false
        val ticket = AccessTicket("request", Long.MAX_VALUE, "123456", "computer", "key")
        compose.setContent { CodyncTheme { AccountScreenContent(fixture().copy(access = ticket),
            AccountScreenActions(cancelAccess = { cancelled = true }), {}) } }
        compose.onNodeWithText("Computers & settings").assertIsDisplayed()
        compose.onNodeWithText("123 456").assertIsDisplayed()
        compose.onNodeWithText("Cancel request").performClick()
        assertTrue(cancelled)
    }

    @Test fun largeTextKeepsTheProfileActionsAccessibleAndTheHeaderClear() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CodyncTheme { AccountScreenContent(fixture(), AccountScreenActions(), {}) }
            }
        }
        val title = compose.onNodeWithText("Account").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close account").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(title.right < close.left)
        compose.onNodeWithText("Scan a computer's code").assertIsDisplayed()
        compose.onNodeWithContentDescription(first.email).assertIsSelected()
        compose.onNodeWithContentDescription("Add account").assertIsDisplayed()
        compose.onNodeWithText("Computers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        snapshot("large-text")
    }

    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("layoutSnapshots") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "account-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
