package io.dualmesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val NeonBlue = Color(0xFF45D8FF)
internal val NeonViolet = Color(0xFF8B7CFF)
internal val SignalGreen = Color(0xFF47F7B1)
internal val WarningAmber = Color(0xFFFFC857)
internal val SignalRed = Color(0xFFFF6482)
internal val GlassSurface = Color(0xD914213A)
internal val GlassSurfaceStrong = Color(0xF0162540)
internal val ModalSurface = Color(0xFF0B172D)
internal val GlassOutline = Color(0x4D78CFFF)
internal val MutedStarlight = Color(0xFF9FB3CE)

private val DeepSpaceTop = Color(0xFF040818)
private val DeepSpaceMiddle = Color(0xFF061125)
private val DeepSpaceBottom = Color(0xFF02050E)

private val DualMeshColors = darkColorScheme(
    primary = NeonBlue,
    onPrimary = Color(0xFF001821),
    primaryContainer = Color(0xD90A4C78),
    onPrimaryContainer = Color(0xFFD7F6FF),
    inversePrimary = Color(0xFF00658B),
    secondary = NeonViolet,
    onSecondary = Color(0xFF100A32),
    secondaryContainer = Color(0xD9362E69),
    onSecondaryContainer = Color(0xFFE8E3FF),
    tertiary = SignalGreen,
    onTertiary = Color(0xFF002118),
    background = DeepSpaceTop,
    onBackground = Color(0xFFF2F7FF),
    surface = GlassSurface,
    onSurface = Color(0xFFF2F7FF),
    surfaceVariant = Color(0xDE17243E),
    onSurfaceVariant = MutedStarlight,
    outline = Color(0xFF58708F),
    outlineVariant = Color(0x6658708F),
    error = SignalRed,
    onError = Color(0xFF2C0010),
)

private val BaseTypography = Typography()
private val DualMeshTypography = Typography(
    titleLarge = BaseTypography.titleLarge.copy(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.15).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = BaseTypography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = BaseTypography.labelSmall.copy(
        fontSize = 11.sp,
        letterSpacing = 0.25.sp,
    ),
)

private val DualMeshShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
)

@Composable
internal fun DualMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DualMeshColors,
        typography = DualMeshTypography,
        shapes = DualMeshShapes,
        content = content,
    )
}

@Composable
internal fun SpaceBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DeepSpaceTop, DeepSpaceMiddle, DeepSpaceBottom),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val blueCenter = Offset(size.width * 0.92f, size.height * 0.12f)
            val blueRadius = size.width * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonBlue.copy(alpha = 0.16f), Color.Transparent),
                    center = blueCenter,
                    radius = blueRadius,
                ),
                radius = blueRadius,
                center = blueCenter,
            )

            val violetCenter = Offset(size.width * 0.02f, size.height * 0.55f)
            val violetRadius = size.width * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonViolet.copy(alpha = 0.10f), Color.Transparent),
                    center = violetCenter,
                    radius = violetRadius,
                ),
                radius = violetRadius,
                center = violetCenter,
            )

            val stars = listOf(
                0.08f to 0.10f,
                0.23f to 0.18f,
                0.43f to 0.09f,
                0.64f to 0.21f,
                0.82f to 0.31f,
                0.14f to 0.39f,
                0.37f to 0.47f,
                0.71f to 0.52f,
                0.91f to 0.61f,
                0.28f to 0.67f,
                0.56f to 0.74f,
                0.11f to 0.83f,
                0.76f to 0.88f,
                0.94f to 0.96f,
            )
            stars.forEachIndexed { index, (x, y) ->
                drawCircle(
                    color = Color.White.copy(alpha = if (index % 3 == 0) 0.20f else 0.10f),
                    radius = if (index % 4 == 0) 1.8f else 1.1f,
                    center = Offset(size.width * x, size.height * y),
                )
            }
        }
        content()
    }
}
