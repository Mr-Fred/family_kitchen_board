package com.familykitchen.board.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.domain.DEFAULT_STARTER_MENU_TEMPLATES
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.MealSlotStatus
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.PlanCadence
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.formatWeekRangeLabel
import com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary
import com.familykitchen.board.domain.sortSlotsChronologically
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Modal bottom sheet entrypoints for weekly board rollover (`StartNewWeekSheet`) and household
// reusable weekly menu templates (`SavedMenusSheet`) with zero slashes or parentheses.

// Short Monday..Sunday labels used in the Pinned Routine Meals preview row.
private val START_WEEK_SHORT_DAYS: List<String> = listOf(
  "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
)

/**
 * Material 3 [ModalBottomSheet] guiding a 1-tap weekly rollover (`startOrRefreshWeeklyBoard`),
 * letting the household select [PlanCadence], protect bought groceries via
 * `Carry Over Uncooked Meals` (`default = true`), and preview pinned routine meals.
 *
 * @param currentBoard Active or outgoing [WeeklyBoard] inspected for uncooked and pinned slots.
 * @param recipesById Lookup map resolving dish titles for pinned routine previews.
 * @param targetWeekStartDateIso ISO-8601 Monday start date for the refreshed or upcoming week.
 * @param initialCadence Default [PlanCadence] pre-selected when opening the sheet.
 * @param onConfirmStartNewWeek Commits the chosen `(cadence, carryOverUncooked)` rollover.
 * @param onDismiss Closes the modal bottom sheet without mutating state.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartNewWeekSheet(
  currentBoard: WeeklyBoard,
  recipesById: Map<String, Recipe> = emptyMap(),
  targetWeekStartDateIso: String = currentBoard.weekStartDateIso,
  initialCadence: PlanCadence = PlanCadence.FULL_WEEK,
  onConfirmStartNewWeek: (cadence: PlanCadence, carryOverUncooked: Boolean) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Expand immediately so all 4 PlanCadence options and the carry-over toggle stay visible.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
    modifier = modifier,
  ) {
    StartNewWeekSheetContent(
      currentBoard = currentBoard,
      recipesById = recipesById,
      targetWeekStartDateIso = targetWeekStartDateIso,
      initialCadence = initialCadence,
      onConfirmStartNewWeek = { cadence, carryOver ->
        onConfirmStartNewWeek(cadence, carryOver)
        onDismiss()
      },
      onDismiss = onDismiss,
    )
  }
}

/**
 * Stateless inner content of [StartNewWeekSheet] extracted for hermetic Compose `@Preview`s.
 *
 * @param currentBoard Active [WeeklyBoard] inspected for uncooked `PLANNED` and `isPinned` slots.
 * @param recipesById Lookup map resolving dish titles for pinned slot previews.
 * @param targetWeekStartDateIso ISO-8601 Monday start date formatted in the header.
 * @param initialCadence Initial [PlanCadence] selection.
 * @param onConfirmStartNewWeek Invoked with `(selectedCadence, carryOverUncooked)` on confirm.
 * @param onDismiss Invoked when closing the sheet.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartNewWeekSheetContent(
  currentBoard: WeeklyBoard,
  recipesById: Map<String, Recipe> = emptyMap(),
  targetWeekStartDateIso: String = currentBoard.weekStartDateIso,
  initialCadence: PlanCadence = PlanCadence.FULL_WEEK,
  onConfirmStartNewWeek: (cadence: PlanCadence, carryOverUncooked: Boolean) -> Unit = { _, _ -> },
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  var selectedCadenceName by rememberSaveable(initialCadence.name) { mutableStateOf(initialCadence.name) }
  val selectedCadence = PlanCadence.entries.find { it.name == selectedCadenceName } ?: initialCadence
  // Default carryOverUncooked to true so bought groceries are protected automatically.
  var carryOverUncooked by rememberSaveable { mutableStateOf(true) }

  // Count uncooked non-leftover unpinned PLANNED slots whose reserved ingredients are protected.
  val uncookedPlannedSlots = remember(currentBoard.slots) {
    sortSlotsChronologically(currentBoard.slots).filter { slot ->
      slot.status == MealSlotStatus.PLANNED &&
        slot.leftoverSourceRecipeId.isNullOrBlank() &&
        !slot.isPinned
    }
  }
  // Collect pinned routine slots preserved on their exact weekday and slot type.
  val pinnedRoutineSlots = remember(currentBoard.slots) {
    sortSlotsChronologically(currentBoard.slots).filter { it.isPinned }
  }

  // Scrollable .imePadding() body resilient on compact 360dp phones at 1.35x font scaling.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header row displaying target Monday..Sunday date span and a 48.dp Close button.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text("Start New Week", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          // Render target week date range ("Nov 23 – Nov 29") in Herbal Sage.
          Text(
            text = formatWeekRangeLabel(targetWeekStartDateIso),
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.herbalSage,
          )
        }
      }
      // 48.dp close icon button dismissing without mutating the active weekly board.
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Close, contentDescription = "Close Start New Week sheet")
      }
    }

    // 1. PlanCadence selector cards (Full Week · Breakfast and Dinner · Dinners Only · Blank Week).
    Text("Choose Weekly Cadence", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PlanCadence.entries.forEach { cadence ->
        val isSelected = selectedCadence == cadence
        // Summarize scheduled slot volume per cadence without slashes or parentheses.
        val slotCountBadge = when (cadence) {
          PlanCadence.FULL_WEEK -> "21 meals"
          PlanCadence.BREAKFAST_AND_DINNER -> "12 meals"
          PlanCadence.DINNERS_ONLY -> "7 dinners"
          PlanCadence.BLANK_WEEK -> "0 pre-filled"
        }
        // >= 56.dp selectable cadence card with 1.dp Herbal Sage highlight border when active.
        Surface(
          onClick = { selectedCadenceName = cadence.name },
          shape = RoundedCornerShape(16.dp),
          color = if (isSelected) extendedColors.sageContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) extendedColors.herbalSage else extendedColors.borderSubtle,
          ),
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = cadence.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = cadence.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // Right-aligned slot count pill.
            Surface(
              shape = CircleShape,
              color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Text(
                text = slotCountBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = extendedColors.onSageContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }

    // 2. Carry Over Uncooked Meals toggle protecting bought groceries from being orphaned.
    // Disable carry-over automatically when the user explicitly selects Blank Week (0 meals).
    val carryEnabled = selectedCadence != PlanCadence.BLANK_WEEK
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .clickable(enabled = carryEnabled) { carryOverUncooked = !carryOverUncooked },
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
          // Show exact count of uncooked planned meals whose purchased groceries are protected.
          Text(
            text = "Carry Over Uncooked Meals · ${uncookedPlannedSlots.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          // Explain grocery protection status clearly for 0, 1, or N uncooked meals.
          val protectNote = when {
            !carryEnabled -> "Blank Week starts completely empty with zero pre-scheduled meals."
            uncookedPlannedSlots.isEmpty() -> "All planned meals on the current board are already completed."
            uncookedPlannedSlots.size == 1 -> "Protects groceries bought for 1 uncooked planned meal."
            else -> "Protects groceries bought for ${uncookedPlannedSlots.size} uncooked planned meals."
          }
          Text(
            text = protectNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Material 3 Switch defaulting ON (`true`) to prevent accidental food waste.
        Switch(
          checked = carryEnabled && carryOverUncooked,
          enabled = carryEnabled,
          onCheckedChange = { carryOverUncooked = it },
        )
      }
    }

    // 3. Pinned Routine Meals preview preserved on their exact weekday & slot.
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = null,
            tint = extendedColors.herbalSage,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Pinned Routine Meals · ${pinnedRoutineSlots.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
        }
        when {
          pinnedRoutineSlots.isEmpty() -> {
            // Teach users how to pin weekly anchors directly from any MealSlotCard.
            Text(
              text = "No pinned routines yet. Use Pin Routine on any meal slot to keep weekly family favorites locked in place.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          else -> {
            // Preview each pinned meal as a compact Day · Dish pill.
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              pinnedRoutineSlots.forEach { slot ->
                val dayText = START_WEEK_SHORT_DAYS.getOrElse(slot.dayIndex.coerceIn(0, 6)) { "Day" }
                val mealTitle = recipesById[slot.recipeId]?.title ?: slot.recipeId
                Surface(
                  shape = CircleShape,
                  color = extendedColors.sageContainer,
                ) {
                  Text(
                    text = "$dayText · $mealTitle",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = extendedColors.onSageContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                  )
                }
              }
            }
          }
        }
      }
    }

    // Primary 52.dp 1-tap confirmation button committing rollover + WeeklyEpisodicRollup in O(1).
    Button(
      onClick = { onConfirmStartNewWeek(selectedCadence, carryEnabled && carryOverUncooked) },
      shape = CircleShape,
      colors = ButtonDefaults.buttonColors(
        containerColor = extendedColors.herbalSage,
        contentColor = Color.White,
      ),
      modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
    ) {
      Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text("Start Fresh Week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }

    Spacer(modifier = Modifier.height(10.dp))
  }
}

/**
 * Material 3 [ModalBottomSheet] presenting reusable weekly [MenuTemplate] blueprints ranked by
 * `rankMenusForHousehold(savedMenus, householdProfile)` (*`French and Yoruba Family Week`* #1 by
 * default), with `Adapt Missing Items to Pantry and Local Market` (`default = true`), 1-tap
 * `Apply Menu`, and inline `Save Current Week as Menu`.
 *
 * @param savedMenus Starter and household-saved [MenuTemplate] list.
 * @param householdProfile Active household profile supplying `preferredCuisines` for ranking.
 * @param currentBoard Optional current [WeeklyBoard] inspected when saving the active week.
 * @param recipesById Lookup map resolving sample dish names on menu cards.
 * @param onApplyMenuTemplate Applies the selected [MenuTemplate] onto the weekly board.
 * @param onSaveCurrentWeekAsMenu Saves the active [WeeklyBoard] as a reusable [MenuTemplate].
 * @param onDismiss Closes the modal bottom sheet.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedMenusSheet(
  savedMenus: List<MenuTemplate> = DEFAULT_STARTER_MENU_TEMPLATES,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  currentBoard: WeeklyBoard? = null,
  recipesById: Map<String, Recipe> = emptyMap(),
  onApplyMenuTemplate: (template: MenuTemplate, adaptToPantryAndMarket: Boolean) -> Unit,
  onSaveCurrentWeekAsMenu: (title: String, subtitle: String) -> Unit = { _, _ -> },
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Expand immediately so the #1 ranked family menu and Save Current Week composer are visible.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = KitchenTheme.extendedColors.linen,
    modifier = modifier,
  ) {
    SavedMenusSheetContent(
      savedMenus = savedMenus,
      householdProfile = householdProfile,
      currentBoard = currentBoard,
      recipesById = recipesById,
      onApplyMenuTemplate = { template, adapt ->
        onApplyMenuTemplate(template, adapt)
        onDismiss()
      },
      onSaveCurrentWeekAsMenu = onSaveCurrentWeekAsMenu,
      onDismiss = onDismiss,
    )
  }
}

/** Warm Daylight preview of `StartNewWeekSheetContent` showing cadence and uncooked protection. */
@Preview(name = "StartNewWeekSheet Daylight", showBackground = true, widthDp = 412, heightDp = 860)
@Composable
fun StartNewWeekSheetPreview() {
  // Pin Monday breakfast so preview demonstrates both Pinned Routine and Carry Over counts.
  val sampleBoard = PreviewFixtures.weeklyBoard.copy(
    slots = PreviewFixtures.weeklyBoard.slots.mapIndexed { idx, slot ->
      if (idx == 0) slot.copy(isPinned = true) else slot
    },
  )
  FamilyKitchenBoardTheme(darkTheme = false) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      StartNewWeekSheetContent(
        currentBoard = sampleBoard,
        recipesById = PreviewFixtures.recipesById,
        targetWeekStartDateIso = "2026-11-23",
      )
    }
  }
}

/** Ambient Counter Dark preview of `SavedMenusSheetContent` with French and Yoruba Family Week #1. */
@Preview(name = "SavedMenusSheet Counter Dark", showBackground = true, widthDp = 412, heightDp = 920)
@Composable
fun SavedMenusSheetDarkPreview() {
  // Merge heritage dishes into recipesById so French and Yoruba dish titles resolve in preview.
  val mergedRecipesById = mergeHeritageRecipesIntoLibrary(PreviewFixtures.recipes)
    .associateBy { it.recipeId }
  FamilyKitchenBoardTheme(darkTheme = true) {
    Surface(color = KitchenTheme.extendedColors.linen) {
      SavedMenusSheetContent(
        savedMenus = DEFAULT_STARTER_MENU_TEMPLATES,
        householdProfile = PreviewFixtures.householdProfile,
        currentBoard = PreviewFixtures.weeklyBoard,
        recipesById = mergedRecipesById,
      )
    }
  }
}
