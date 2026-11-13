package com.turnypt.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavArgument
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.turnypt.app.ContactsViewModel
import com.turnypt.app.LocalBiometrics
import com.turnypt.app.MainViewModel
import com.turnypt.app.R
import com.turnypt.app.SettingsViewModel
import com.turnypt.app.ToolsViewModel
import com.turnypt.app.companion.DestinationRoute
import com.turnypt.app.security.BiometricKeyStore

/**
 * Five-tab main navigation (v0.3.0): Home, Encrypt, Decrypt, Contacts,
 * Settings. The Encrypt and Decrypt tabs host the in-app tools (manual
 * input); everything else stays where it was.
 */
private data class Destination(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun TurnyptApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    val destinations = listOf(
        Destination(DestinationRoute.HOME, R.string.nav_home, TurnyptIcons.Home),
        Destination(DestinationRoute.ENCRYPT, R.string.nav_encrypt, TurnyptIcons.Lock),
        Destination(DestinationRoute.DECRYPT, R.string.nav_decrypt, TurnyptIcons.LockOpen),
        Destination(DestinationRoute.CONTACTS, R.string.nav_contacts, TurnyptIcons.People),
        Destination(DestinationRoute.SETTINGS, R.string.nav_settings, TurnyptIcons.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    // v1.2.0 (user report: "the bottom navigation bar fails to
                    // reflect the active page, and taps seem dead"): the
                    // Encrypt/Decrypt destinations live in the graph under
                    // QUERY-PATTERN routes ("encrypt?mode={mode}") — the old
                    // exact `it.route == destination.route` compared
                    // "encrypt" against that pattern and NEVER matched, so
                    // both tabs stayed unselected no matter what was on
                    // screen (and a retap, restoring the same state, looked
                    // like a dead click). Matching on the base route — the
                    // pattern before the '?' — keeps every tab honestly
                    // selected, whatever arguments it was opened with.
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route?.substringBefore('?') == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            val currentBase =
                                backStackEntry?.destination?.route?.substringBefore('?')
                            if (currentBase == destination.route) {
                                // Already on this tab. If it was deep-linked
                                // with non-default arguments (e.g. the image
                                // segment via Home), one tap returns the tab
                                // to its default state — a visible, honest
                                // response; at the default it is a no-op,
                                // like every standard bottom bar.
                                val mode = backStackEntry?.arguments
                                    ?.getString(DestinationRoute.ARG_MODE)
                                if (mode != null && mode != DestinationRoute.MODE_TEXT) {
                                    navController.navigate(destination.route) {
                                        launchSingleTop = true
                                        restoreState = false
                                    }
                                }
                            } else {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes), fontSize = 12.sp) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DestinationRoute.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(DestinationRoute.HOME) {
                HomeScreen(
                    viewModel = hiltViewModel<MainViewModel>(),
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            // v0.6.0: optional mode argument (text | image) — Quick Access
            // deep-links into the image segments of both tabs.
            composable(
                "${DestinationRoute.ENCRYPT}?${DestinationRoute.ARG_MODE}={${DestinationRoute.ARG_MODE}}",
                arguments = listOf(
                    navArgument(DestinationRoute.ARG_MODE) {
                        defaultValue = DestinationRoute.MODE_TEXT
                    },
                ),
            ) { entry ->
                val mode = entry.arguments?.getString(DestinationRoute.ARG_MODE)
                EncryptScreen(
                    viewModel = hiltViewModel<ToolsViewModel>(),
                    biometrics = LocalBiometrics.current,
                    initialImage = mode == DestinationRoute.MODE_IMAGE,
                )
            }
            composable(
                "${DestinationRoute.DECRYPT}?${DestinationRoute.ARG_MODE}={${DestinationRoute.ARG_MODE}}",
                arguments = listOf(
                    navArgument(DestinationRoute.ARG_MODE) {
                        defaultValue = DestinationRoute.MODE_TEXT
                    },
                ),
            ) { entry ->
                val mode = entry.arguments?.getString(DestinationRoute.ARG_MODE)
                DecryptScreen(
                    viewModel = hiltViewModel<ToolsViewModel>(),
                    biometrics = LocalBiometrics.current,
                    initialImage = mode == DestinationRoute.MODE_IMAGE,
                )
            }
            composable(DestinationRoute.CONTACTS) {
                ContactsScreen(
                    viewModel = hiltViewModel<ContactsViewModel>(),
                    biometrics = LocalBiometrics.current,
                )
            }
            composable(DestinationRoute.SETTINGS) {
                SettingsScreen(
                    viewModel = hiltViewModel<SettingsViewModel>(),
                    contactsViewModel = hiltViewModel<ContactsViewModel>(),
                )
            }
        }
    }
}
