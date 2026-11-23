package com.familykitchen.board.ui.cookmode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.PostCookCompletionSummary
import com.familykitchen.board.domain.PostCookReactionTag
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// SavorAI Post-Cook Completion Sheet (`PostCookCompletionSheet`):
// Celebrates finished dishes, offers a 1-tap `Save N Servings for Tomorrow Lunch` (`0`-BOM) toggle
// defaulted ON when extraLeftoverServings > 0, and collects 1-tap ElephantMemory reaction tags.

/**
 * Material 3 [ModalBottomSheet] displayed immediately after completing a recipe in Step-by-Step
 * Cook Mode, enabling 1-tap `0`-BOM Leftover Lunch scheduling (`Save N Servings for Tomorrow Lunch`,
 * defaulted `ON` when `summary.extraLeftoverServings > 0`) and optional family reaction chips
 * (`Kids Loved` · `Adjust Spice` · `Quick Win`) that train `HouseholdElephantMemory`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCookCompletionSheet(
  summary: PostCookCompletionSummary,
  onConfirmPostCook: (Boolean, PostCookReactionTag?) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Open fully expanded so leftover toggle and Done CTA are reachable without extra scrolling.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss, sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.background, modifier = modifier,
  ) {
    PostCookCompletionSheetContent(summary = summary, onConfirmPostCook = onConfirmPostCook, onDismiss = onDismiss)
  }
}

/**
 * Stateless inner content of [PostCookCompletionSheet] enabling hermetic `@Preview` rendering
 * without a modal window host.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostCookCompletionSheetContent(
  summary: PostCookCompletionSummary,
  onConfirmPostCook: (Boolean, PostCookReactionTag?) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Default ON automatically whenever batch cooking produced extra leftover servings (> 0).
  var saveLeftoverLunch by rememberSaveable(summary.recipeId, summary.extraLeftoverServings) {
    mutableStateOf(summary.extraLeftoverServings > 0)
  }
  // Optional 1-tap reaction tag persisted into HouseholdElephantMemory when confirmed.
  var selectedReactionName by rememberSaveable(summary.recipeId) { mutableStateOf<String?>(null) }
  val selectedReaction = PostCookReactionTag.entries.find { it.name == selectedReactionName }

  // Highlight exact leftover serving count when batch cooking yielded surplus portions.
  val leftoverHeading = when {
    summary.extraLeftoverServings > 0 -> "Save ${summary.extraLeftoverServings} Servings for Tomorrow Lunch"
    else -> "Save Extra Servings for Tomorrow Lunch"
  }

  Column(
    modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
      .padding(horizontal = 18.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Celebratory Dish Header with CheckCircle emblem and 48.dp Close action.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
      Row(
        modifier = Modifier.weight(1f).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(extendedColors.cookedSuccessContainer), contentAlignment = Alignment.Center) {
          Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = extendedColors.onCookedSuccess, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Meal Complete", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extendedColors.herbalSage)
          Text(summary.recipeTitle, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text("Pantry stock updated · ${summary.cookedServings} servings prepared", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss post cook sheet")
      }
    }

    // 2. 1-Tap Tomorrow Leftover Lunch Toggle Card (default ON when extraLeftoverServings > 0).
    Card(
      onClick = { saveLeftoverLunch = !saveLeftoverLunch }, modifier = Modifier.fillMaxWidth(),
      shape = KitchenTheme.shapes.card2xl,
      colors = CardDefaults.cardColors(
        containerColor = if (saveLeftoverLunch) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
      ),
      border = BorderStroke(1.dp, if (saveLeftoverLunch) extendedColors.herbalSage else extendedColors.borderSubtle),
    ) {
      // 0-BOM leftover lunch prevents Smart Grocery from double-buying ingredients already cooked.
      Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f).padding(end = 10.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
              .background(if (saveLeftoverLunch) extendedColors.herbalSage else extendedColors.parchment),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Restaurant, contentDescription = null,
              tint = if (saveLeftoverLunch) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = leftoverHeading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
              color = if (saveLeftoverLunch) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Schedules tomorrow lunch with zero extra grocery items",
              style = MaterialTheme.typography.bodySmall,
              color = if (saveLeftoverLunch) extendedColors.onSageContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Switch(checked = saveLeftoverLunch, onCheckedChange = { saveLeftoverLunch = it })
      }
    }

    // 3. Optional 1-Tap Family Reaction Chips (`Kids Loved · Adjust Spice · Quick Win`).
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("How did the family like it?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text("Optional feedback remembered for future weekly meal plans", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      // FlowRow wraps 48.dp reaction chips cleanly on 360dp viewports at 1.35x font scale.
      FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PostCookReactionTag.entries.forEach { tag ->
          val isSelected = selectedReaction == tag
          Surface(
            shape = CircleShape,
            color = if (isSelected) extendedColors.herbalSage else extendedColors.parchment,
            border = if (isSelected) null else BorderStroke(1.dp, extendedColors.borderSubtle),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).clip(CircleShape)
              .clickable { selectedReactionName = if (isSelected) null else tag.name },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
              Text(
                text = tag.chipLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, softWrap = false,
              )
            }
          }
        }
      }
    }

    // 4. Primary 52.dp Done button committing leftover lunch and ElephantMemory reaction in 1 tap.
    Button(
      onClick = { onConfirmPostCook(saveLeftoverLunch, selectedReaction) }, shape = CircleShape,
      colors = ButtonDefaults.buttonColors(containerColor = extendedColors.herbalSage, contentColor = Color.White),
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(bottom = 12.dp),
    ) {
      Text("Done", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
  }
}

@Preview(name = "Post-Cook Sheet — Batch 2x Leftovers Daylight", showBackground = true, widthDp = 360)
@Composable
private fun PostCookCompletionSheetLeftoverPreview() {
  // Verifies default-ON `Save 2 Servings for Tomorrow Lunch` toggle on 360dp Warm Daylight.
  val summary = PostCookCompletionSummary(
    recipeId = "rec_31_yoruba_efo_riro", recipeTitle = "Yoruba Efo Riro Rich Vegetable Stew",
    cookedSlotId = "board_week_1_day_0_dinner", cookedDayIndex = 0,
    cookedServings = 6, householdServings = 4, extraLeftoverServings = 2,
  )
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      PostCookCompletionSheetContent(summary = summary, onConfirmPostCook = { _, _ -> }, onDismiss = {})
    }
  }
}

@Preview(name = "Post-Cook Sheet — Standard Servings Dark", showBackground = true, widthDp = 412)
@Composable
private fun PostCookCompletionSheetDarkPreview() {
  // Verifies standard 4-serving completion and Counter Dark contrast.
  val summary = PostCookCompletionSummary(
    recipeId = "rec_20_one_pan_lemon_herb_salmon_rice",
    recipeTitle = "Pan-Seared Tuscan Salmon with Creamy Garlic Lemon Spinach",
    cookedSlotId = "board_week_1_day_2_dinner", cookedDayIndex = 2,
    cookedServings = 4, householdServings = 4, extraLeftoverServings = 0,
  )
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      PostCookCompletionSheetContent(summary = summary, onConfirmPostCook = { _, _ -> }, onDismiss = {})
    }
  }
}
