package com.familykitchen.board.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Artisanal SavorAI Compose Brand Emblem matching the adaptive launcher icon 1:1:
// Herbal Sage Dutch-oven cocotte + Terracotta domed lid + Honey knob + Botanical sage sprig & steam.

private const val LOGO_GRID_UNITS: Float = 60f

/**
 * Renders the bespoke `SavorAI` culinary cocotte vector mark on a Compose [Canvas] using active
 * [KitchenTheme.extendedColors] (`herbalSage`, `terracotta`, `primaryFixed`, `sageContainer`, and
 * `warmApricot`) so it scales crisply from `14.dp` chat avatars up to `56.dp` hero emblems in both
 * Warm Daylight and Ambient Counter Dark modes (zero sparkles, zero cold blue/purple hues).
 *
 * @param modifier Compose [Modifier] controlling rendered bounds (`20.dp` default).
 */
@Composable
fun SavorBrandLogoMark(
  modifier: Modifier = Modifier.size(20.dp),
) {
  val extendedColors = KitchenTheme.extendedColors
  val potColor = extendedColors.herbalSage
  val lidColor = extendedColors.terracotta
  val knobColor = extendedColors.warmApricot
  val secondaryLeafColor = lerp(extendedColors.herbalSage, extendedColors.primaryFixed, 0.38f)
  val ceramicHighlightColor = extendedColors.sageContainer.copy(alpha = 0.88f)

  Canvas(modifier = modifier) {
    val side = size.minDimension
    if (side <= 0f) return@Canvas
    val originX = (size.width - side) * 0.5f
    val originY = (size.height - side) * 0.5f

    // Normalize 60x60 safe-zone coordinates proportionally across 14.dp..56.dp icon bounds.
    fun gx(value: Float): Float = originX + (value / LOGO_GRID_UNITS) * side
    fun gy(value: Float): Float = originY + (value / LOGO_GRID_UNITS) * side
    fun gs(value: Float): Float = (value / LOGO_GRID_UNITS) * side

    // 1. Cast-iron cocotte side loop handles.
    val handlesPath = Path().apply {
      moveTo(gx(9.5f), gy(28.5f))
      cubicTo(gx(3.5f), gy(28.5f), gx(3.5f), gy(36.5f), gx(10f), gy(36.5f))
      moveTo(gx(50.5f), gy(28.5f))
      cubicTo(gx(56.5f), gy(28.5f), gx(56.5f), gy(36.5f), gx(50f), gy(36.5f))
    }
    drawPath(
      path = handlesPath,
      color = potColor,
      style = Stroke(width = gs(3.4f), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // 2. Dutch-oven top collar rim + rounded cast-iron pot belly.
    drawRoundRect(
      color = potColor,
      topLeft = Offset(gx(6f), gy(25f)),
      size = Size(gs(48f), gs(3.4f)),
      cornerRadius = CornerRadius(gs(1.7f), gs(1.7f)),
    )
    val potBellyPath = Path().apply {
      moveTo(gx(8.5f), gy(27.5f))
      lineTo(gx(51.5f), gy(27.5f))
      lineTo(gx(50.4f), gy(38.6f))
      cubicTo(gx(49.7f), gy(46.2f), gx(43.3f), gy(52f), gx(35f), gy(52f))
      lineTo(gx(25f), gy(52f))
      cubicTo(gx(16.7f), gy(52f), gx(10.3f), gy(46.2f), gx(9.6f), gy(38.6f))
      close()
    }
    drawPath(path = potBellyPath, color = potColor)

    // 3. Subtle warm ceramic highlight curve along the lower-left cocotte bowl.
    val highlightPath = Path().apply {
      moveTo(gx(15.5f), gy(39.2f))
      cubicTo(gx(16.8f), gy(44.6f), gx(21.4f), gy(47.4f), gx(28.2f), gy(47.4f))
    }
    drawPath(
      path = highlightPath,
      color = ceramicHighlightColor,
      style = Stroke(width = gs(2.4f), cap = StrokeCap.Round),
    )

    // 4. Fitted Terracotta domed lid + Golden Honey lid knob centered above the dome.
    val domedLidPath = Path().apply {
      moveTo(gx(7f), gy(23.6f))
      cubicTo(gx(7f), gy(22.2f), gx(8.1f), gy(21.2f), gx(9.5f), gy(21.2f))
      lineTo(gx(11.2f), gy(21.2f))
      cubicTo(gx(14.8f), gy(17.5f), gx(21.6f), gy(16f), gx(30f), gy(16f))
      cubicTo(gx(38.4f), gy(16f), gx(45.2f), gy(17.5f), gx(48.8f), gy(21.2f))
      lineTo(gx(50.5f), gy(21.2f))
      cubicTo(gx(51.9f), gy(21.2f), gx(53f), gy(22.2f), gx(53f), gy(23.6f))
      close()
    }
    drawPath(path = domedLidPath, color = lidColor)
    drawCircle(
      color = knobColor,
      radius = gs(2.9f),
      center = Offset(gx(30f), gy(12.8f)),
    )

    // 5. Two-leaf botanical sage sprig + soft rising terracotta culinary steam curl.
    val primaryLeafPath = Path().apply {
      moveTo(gx(26.8f), gy(10.2f))
      cubicTo(gx(22f), gy(9.6f), gx(19.4f), gy(6.2f), gx(19.4f), gy(2f))
      cubicTo(gx(23.6f), gy(2f), gx(26.8f), gy(5.2f), gx(26.8f), gy(10.2f))
      close()
    }
    drawPath(path = primaryLeafPath, color = potColor)

    val secondaryLeafPath = Path().apply {
      moveTo(gx(27.6f), gy(9.6f))
      cubicTo(gx(25.6f), gy(5.6f), gx(27.6f), gy(1.4f), gx(31.8f), gy(0.5f))
      cubicTo(gx(32.4f), gy(4.6f), gx(30.6f), gy(8.1f), gx(27.6f), gy(9.6f))
      close()
    }
    drawPath(path = secondaryLeafPath, color = secondaryLeafColor)

    val steamCurlPath = Path().apply {
      moveTo(gx(36.2f), gy(10.4f))
      cubicTo(gx(34.2f), gy(8.1f), gx(39.4f), gy(5.4f), gx(37f), gy(1f))
    }
    drawPath(
      path = steamCurlPath,
      color = lidColor,
      style = Stroke(width = gs(2.5f), cap = StrokeCap.Round),
    )
  }
}

/**
 * Framed `SavorAI` brand badge centering [SavorBrandLogoMark] inside a soft `sageContainer`
 * surface with a crisp `1.dp` `herbalSage` (`28%` alpha) botanical border.
 *
 * @param badgeSize Outer badge container dimension (`32.dp` default).
 * @param logoSize Inner [SavorBrandLogoMark] dimension (`20.dp` default).
 * @param shape Outer container shape (`RoundedCornerShape(10.dp)` or `CircleShape`).
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun SavorBrandBadge(
  badgeSize: Dp = 32.dp,
  logoSize: Dp = 20.dp,
  shape: Shape = RoundedCornerShape(10.dp),
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Soft sage badge with subtle 1.dp botanical border frames the cocotte mark cleanly.
  Surface(
    shape = shape,
    color = extendedColors.sageContainer,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.28f)),
    shadowElevation = 0.dp,
    modifier = modifier.size(badgeSize),
  ) {
    Box(contentAlignment = Alignment.Center) {
      SavorBrandLogoMark(modifier = Modifier.size(logoSize))
    }
  }
}

@Preview(name = "SavorAI Brand Emblem — Warm Daylight", showBackground = true, widthDp = 360)
@Composable
private fun SavorBrandLogoDaylightPreview() {
  // Verify crisp rendering from 14.dp chat avatars up to 56.dp hero marks in Warm Daylight.
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SavorAI Brand Scale (Warm Daylight)", style = MaterialTheme.typography.titleSmall)
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SavorBrandBadge(badgeSize = 26.dp, logoSize = 15.dp, shape = CircleShape)
          SavorBrandBadge(badgeSize = 34.dp, logoSize = 20.dp, shape = RoundedCornerShape(11.dp))
          SavorBrandBadge(badgeSize = 40.dp, logoSize = 24.dp, shape = RoundedCornerShape(12.dp))
          SavorBrandBadge(badgeSize = 52.dp, logoSize = 32.dp, shape = RoundedCornerShape(16.dp))
          SavorBrandLogoMark(modifier = Modifier.size(56.dp))
        }
      }
    }
  }
}

@Preview(name = "SavorAI Brand Emblem — Counter Dark", showBackground = true, widthDp = 360)
@Composable
private fun SavorBrandLogoCounterDarkPreview() {
  // Verify luminous mint-sage & warm coral-terracotta contrast on OLED Counter Dark surfaces.
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SavorAI Brand Scale (Counter Dark)", style = MaterialTheme.typography.titleSmall)
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SavorBrandBadge(badgeSize = 26.dp, logoSize = 15.dp, shape = CircleShape)
          SavorBrandBadge(badgeSize = 34.dp, logoSize = 20.dp, shape = RoundedCornerShape(11.dp))
          SavorBrandBadge(badgeSize = 40.dp, logoSize = 24.dp, shape = RoundedCornerShape(12.dp))
          SavorBrandBadge(badgeSize = 52.dp, logoSize = 32.dp, shape = RoundedCornerShape(16.dp))
          SavorBrandLogoMark(modifier = Modifier.size(56.dp))
        }
      }
    }
  }
}
