package com.turnypt.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * v0.5.0 palette ("Calm Vault", see docs/DESIGN.md).
 *
 * v0.4.1 defined the core indigo/slate/ochre scheme; v0.5 completes the
 * Material 3 role set — the surface-container tier, inverse roles,
 * outlineVariant and scrim — so grouped surfaces are built from tonal
 * containers instead of borders, exactly as the M3 role system prescribes
 * (roles are only ever used in their accessible pairs).
 *
 * The ochre tertiary stays: it renders the "fair" passphrase hint, so
 * "could be stronger" keeps reading warm rather than alarming.
 */

// Light
val LightPrimary = Color(0xFF4A5FC1)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDEE3FF)
val LightOnPrimaryContainer = Color(0xFF0D1B66)
val LightSecondary = Color(0xFF5B7390)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDBE2F0)
val LightOnSecondaryContainer = Color(0xFF1A2A40)
val LightTertiary = Color(0xFF7C5800)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDF9E)
val LightOnTertiaryContainer = Color(0xFF261A00)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightBackground = Color(0xFFFCFCFE)
val LightOnBackground = Color(0xFF1A1B20)
val LightSurface = Color(0xFFFCFCFE)
val LightOnSurface = Color(0xFF1A1B20)
val LightSurfaceVariant = Color(0xFFE3E4EC)
val LightOnSurfaceVariant = Color(0xFF46474F)
val LightOutline = Color(0xFF777782)
val LightOutlineVariant = Color(0xFFC6C6D1)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF6F6FA)
val LightSurfaceContainer = Color(0xFFF0F1F6)
val LightSurfaceContainerHigh = Color(0xFFEAEBF1)
val LightSurfaceContainerHighest = Color(0xFFE4E5EB)
val LightSurfaceDim = Color(0xFFDCDCE4)
val LightSurfaceBright = Color(0xFFFCFCFE)
val LightInverseSurface = Color(0xFF2F3036)
val LightInverseOnSurface = Color(0xFFF1F0F7)
val LightInversePrimary = Color(0xFFBAC3FF)
val LightScrim = Color(0xFF000000)

// Dark
val DarkPrimary = Color(0xFFBAC3FF)
val DarkOnPrimary = Color(0xFF1B2A6B)
val DarkPrimaryContainer = Color(0xFF334199)
val DarkOnPrimaryContainer = Color(0xFFDEE3FF)
val DarkSecondary = Color(0xFFBFC7DC)
val DarkOnSecondary = Color(0xFF2A3142)
val DarkSecondaryContainer = Color(0xFF404759)
val DarkOnSecondaryContainer = Color(0xFFDBE2F0)
val DarkTertiary = Color(0xFFF2BE4A)
val DarkOnTertiary = Color(0xFF402D00)
val DarkTertiaryContainer = Color(0xFF5C4200)
val DarkOnTertiaryContainer = Color(0xFFFFDF9E)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkBackground = Color(0xFF121318)
val DarkOnBackground = Color(0xFFE3E2E8)
val DarkSurface = Color(0xFF16171C)
val DarkOnSurface = Color(0xFFE3E2E8)
val DarkSurfaceVariant = Color(0xFF45474F)
val DarkOnSurfaceVariant = Color(0xFFC6C5CE)
val DarkOutline = Color(0xFF90909B)
val DarkOutlineVariant = Color(0xFF44464E)
val DarkSurfaceContainerLowest = Color(0xFF0D0E12)
val DarkSurfaceContainerLow = Color(0xFF191A20)
val DarkSurfaceContainer = Color(0xFF1D1E24)
val DarkSurfaceContainerHigh = Color(0xFF27282F)
val DarkSurfaceContainerHighest = Color(0xFF32333A)
val DarkSurfaceDim = Color(0xFF121318)
val DarkSurfaceBright = Color(0xFF38393F)
val DarkInverseSurface = Color(0xFFE3E2E8)
val DarkInverseOnSurface = Color(0xFF2F3036)
val DarkInversePrimary = Color(0xFF334199)
val DarkScrim = Color(0xFF000000)

/**
 * v0.5.0 semantic extension: a SUCCESS tone, which Material 3 lacks.
 *
 * Used ONLY for the unlocked/secure state (chips, avatars, banners) — the
 * vault-app pattern: the brand color never has to mean two things, and
 * "unlocked" gets its own unmistakable channel. Pairs are hand-checked
 * for contrast in both themes (docs/DESIGN.md §3.1).
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LightSemantic = SemanticColors(
    success = Color(0xFF1F6D33),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC9EED2),
    onSuccessContainer = Color(0xFF0A2E14),
)

val DarkSemantic = SemanticColors(
    success = Color(0xFF8BD9A0),
    onSuccess = Color(0xFF00391B),
    successContainer = Color(0xFF1E5B34),
    onSuccessContainer = Color(0xFFC9EED2),
)
