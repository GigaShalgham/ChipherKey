package org.cipherkey.app.ui

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
import org.cipherkey.app.ContactsViewModel
import org.cipherkey.app.LocalBiometrics
import org.cipherkey.app.MainViewModel
import org.cipherkey.app.R
import org.cipherkey.app.SettingsViewModel
import org.cipherkey.app.ToolsViewModel
import org.cipherkey.app.companion.DestinationRoute
import org.cipherkey.app.security.BiometricKeyStore

/**
 * Five-tab main navigation (v0.3.0): Home, Encrypt, Decrypt, Contacts,
 * Settings. The Encrypt and Decrypt tabs host the in-app tools (manual
 * input); everything else stays where it was.
 */
private data class Destination(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun CipherKeyApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    val destinations = listOf(
        Destination(DestinationRoute.HOME, R.string.nav_home, CipherKeyIcons.Home),
        Destination(DestinationRoute.ENCRYPT, R.string.nav_encrypt, CipherKeyIcons.Lock),
        Destination(DestinationRoute.DECRYPT, R.string.nav_decrypt, CipherKeyIcons.LockOpen),
        Destination(DestinationRoute.CONTACTS, R.string.nav_contacts, CipherKeyIcons.People),
        Destination(DestinationRoute.SETTINGS, R.string.nav_settings, CipherKeyIcons.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
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
