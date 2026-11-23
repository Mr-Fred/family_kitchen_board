package com.familykitchen.board.ui.board

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familykitchen.board.domain.CookOwner
import com.familykitchen.board.domain.DEFAULT_STARTER_MENU_TEMPLATES
import com.familykitchen.board.domain.HouseholdProfile
import com.familykitchen.board.domain.MealSlotType
import com.familykitchen.board.domain.MenuTemplate
import com.familykitchen.board.domain.Recipe
import com.familykitchen.board.domain.WeeklyBoard
import com.familykitchen.board.domain.doesRecipeMatchPreferredCuisines
import com.familykitchen.board.domain.mergeHeritageRecipesIntoLibrary
import com.familykitchen.board.domain.rankMenusForHousehold
import com.familykitchen.board.ui.preview.PreviewFixtures
import com.familykitchen.board.ui.theme.EditorialSerifFontFamily
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Stateless inner content and ranked MenuTemplate cards for SavedMenusSheet, keeping
// French and Yoruba Family Week ranked #1 by default and supporting inline menu saving.

/**
 * Stateless inner content of [SavedMenusSheet] extracted for hermetic Compose `@Preview`s.
 *
 * @param savedMenus Starter and household-saved [MenuTemplate] list.
 * @param householdProfile Active household profile supplying `preferredCuisines` for ranking.
 * @param currentBoard Optional current [WeeklyBoard] inspected when saving the active week.
 * @param recipesById Lookup map resolving sample dish names on menu cards.
 * @param onApplyMenuTemplate Invoked with `(template, adaptToPantryAndMarket)` on `Apply Menu`.
 * @param onSaveCurrentWeekAsMenu Invoked with `(title, subtitle)` when saving the active week.
 * @param onDismiss Invoked when closing the sheet.
 * @param modifier Optional Compose [Modifier].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedMenusSheetContent(
  savedMenus: List<MenuTemplate> = DEFAULT_STARTER_MENU_TEMPLATES,
  householdProfile: HouseholdProfile = HouseholdProfile(),
  currentBoard: WeeklyBoard? = null,
  recipesById: Map<String, Recipe> = emptyMap(),
  onApplyMenuTemplate: (template: MenuTemplate, adaptToPantryAndMarket: Boolean) -> Unit = { _, _ -> },
  onSaveCurrentWeekAsMenu: (title: String, subtitle: String) -> Unit = { _, _ -> },
  onDismiss: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Default pantry & market adaptation to true so missing items auto-swap to stocked dishes.
  var adaptToPantryAndMarket by rememberSaveable { mutableStateOf(true) }
  var showSaveComposer by rememberSaveable { mutableStateOf(false) }
  var customMenuTitle by rememberSaveable { mutableStateOf("") }
  var customMenuSubtitle by rememberSaveable { mutableStateOf("") }

  // Rank menus deterministically so French and Yoruba Family Week leads by default.
  val rankedMenus = remember(savedMenus, householdProfile) {
    rankMenusForHousehold(savedMenus.ifEmpty { DEFAULT_STARTER_MENU_TEMPLATES }, householdProfile)
  }
  // Allow saving whenever currentBoard has at least 1 scheduled meal (or in standalone preview).
  val canSaveBoard = currentBoard == null || currentBoard.slots.isNotEmpty()

  // Scrollable .imePadding() body resilient down to 360dp width at 1.35x font scaling.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Sheet header displaying active household cuisine ranking context + 48.dp Close button.
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AutoStories, contentDescription = null, tint = extendedColors.herbalSage)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text("Saved Family Menus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          // Summarize active preferredCuisines driving rankMenusForHousehold ordering.
          Text(
            text = "Ranked for ${householdProfile.preferredCuisines.joinToString(" · ").ifEmpty { "your household" }}",
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.herbalSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      // 48.dp close icon button dismissing the modal sheet cleanly.
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Close, contentDescription = "Close Saved Menus sheet")
      }
    }

    // Market & pantry adaptation toggle (default = true).
    // Full-row >= 56.dp clickable parchment card toggling automatic EC-02/EC-03 dish remixing.
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = extendedColors.parchment,
      border = BorderStroke(1.dp, extendedColors.borderSubtle),
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .clickable { adaptToPantryAndMarket = !adaptToPantryAndMarket },
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
          Text(
            text = "Adapt Missing Items to Pantry and Local Market",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          // Clarify that unavailable or unstocked blueprint dishes swap to stocked family matches.
          Text(
            text = "Automatically swaps unavailable or unstocked dishes to matching stocked recipes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Switch defaults ON so applied menus work out-of-the-box in any local market.
        Switch(
          checked = adaptToPantryAndMarket,
          onCheckedChange = { adaptToPantryAndMarket = it },
        )
      }
    }

    // Inline Save Current Week as Menu section.
    savedMenusInlineComposerCard(
      canSaveBoard = canSaveBoard,
      showSaveComposer = showSaveComposer,
      customMenuTitle = customMenuTitle,
      customMenuSubtitle = customMenuSubtitle,
      onToggleComposer = { showSaveComposer = !showSaveComposer },
      onTitleChange = { customMenuTitle = it },
      onSubtitleChange = { customMenuSubtitle = it },
      onCommitSave = {
        val cleanTitle = savedMenusSanitizeText(customMenuTitle)
        if (cleanTitle.isNotEmpty()) {
          val cleanSub = savedMenusSanitizeText(customMenuSubtitle)
            .ifEmpty { "Saved household weekly meal plan" }
          onSaveCurrentWeekAsMenu(cleanTitle, cleanSub)
          customMenuTitle = ""
          customMenuSubtitle = ""
          showSaveComposer = false
        }
      },
    )

    HorizontalDivider(color = extendedColors.borderSubtle)

    // Render ranked MenuTemplate cards with Top Family Match highlight on #1 matching menu.
    rankedMenus.forEachIndexed { index, menu ->
      savedMenusTemplateCard(
        menu = menu,
        isTopRanked = index == 0,
        householdProfile = householdProfile,
        recipesById = recipesById,
        onApply = { onApplyMenuTemplate(menu, adaptToPantryAndMarket) },
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@Composable
private fun savedMenusInlineComposerCard(
  canSaveBoard: Boolean,
  showSaveComposer: Boolean,
  customMenuTitle: String,
  customMenuSubtitle: String,
  onToggleComposer: () -> Unit,
  onTitleChange: (String) -> Unit,
  onSubtitleChange: (String) -> Unit,
  onCommitSave: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Collapsible composer card allowing families to snapshot any working week as a reusable menu.
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 48.dp toggle button opening or collapsing the inline menu snapshot form.
      OutlinedButton(
        onClick = onToggleComposer,
        enabled = canSaveBoard,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Save Current Week as Menu", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }

      // Expandable title + optional notes inputs saving the active WeeklyBoard as a MenuTemplate.
      AnimatedVisibility(visible = showSaveComposer) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = customMenuTitle,
            onValueChange = onTitleChange,
            label = { Text("Menu Title") },
            placeholder = { Text("Busy School and Bistro Week") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Words,
              imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = customMenuSubtitle,
            onValueChange = onSubtitleChange,
            label = { Text("Menu Notes · Optional") },
            placeholder = { Text("Fast mornings and favorite family suppers") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Sentences,
              imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommitSave() }),
            modifier = Modifier.fillMaxWidth(),
          )
          // 48.dp Save Menu confirmation button enabled as soon as Menu Title is non-blank.
          Button(
            onClick = onCommitSave,
            enabled = savedMenusSanitizeText(customMenuTitle).isNotEmpty(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
              containerColor = extendedColors.herbalSage,
              contentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
          ) {
            Text("Save Menu", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun savedMenusTemplateCard(
  menu: MenuTemplate,
  isTopRanked: Boolean,
  householdProfile: HouseholdProfile,
  recipesById: Map<String, Recipe>,
  onApply: () -> Unit,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Check whether any menu cuisineFocus tag matches householdProfile.preferredCuisines.
  val matchesHousehold = menu.cuisineFocus.any { focusTag ->
    val probe = Recipe(
      recipeId = "probe_${menu.menuId}",
      title = focusTag,
      sourceAttribution = focusTag,
      allowedSlots = listOf(MealSlotType.DINNER),
      preferredOwner = CookOwner.WIFE,
      rawPrepMinutes = 5,
      cookMinutes = 10,
      calibratedActiveMinutes = 17,
      bomLines = emptyList(),
      cuisineTag = focusTag,
      cuisineTags = listOf(focusTag),
    )
    doesRecipeMatchPreferredCuisines(probe, householdProfile.preferredCuisines)
  }
  // Extract up to 3 distinct dish titles to preview the menu's flavor profile at a glance.
  val previewDishes = remember(menu.slotSpecs, recipesById) {
    menu.slotSpecs
      .mapNotNull { spec -> recipesById[spec.recipeId]?.title }
      .distinct()
      .take(3)
  }

  // Highlight the #1 household-matched menu (French and Yoruba Family Week by default) in Sage.
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(
      width = 1.dp,
      color = if (isTopRanked && matchesHousehold) extendedColors.herbalSage else extendedColors.borderSubtle,
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Top metadata badge strip: Top Family Match, Saved by Family, and meal count.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (isTopRanked && matchesHousehold) {
          Surface(shape = CircleShape, color = extendedColors.sageContainer) {
            Text(
              text = "Top Family Match",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = extendedColors.onSageContainer,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
        if (menu.isUserCreated) {
          Surface(shape = CircleShape, color = extendedColors.tertiaryFixed) {
            Text(
              text = "Saved by Family",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = extendedColors.onTertiaryFixed,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
        Surface(
          shape = CircleShape,
          color = extendedColors.parchment,
          border = BorderStroke(1.dp, extendedColors.borderSubtle),
        ) {
          Text(
            text = "${menu.slotSpecs.size} meals",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // Editorial Newsreader 20.sp menu title + calm subtitle.
      Text(
        text = savedMenusSanitizeText(menu.title),
        style = MaterialTheme.typography.headlineSmall.copy(
          fontFamily = EditorialSerifFontFamily,
          fontSize = 20.sp,
          lineHeight = 25.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = savedMenusSanitizeText(menu.subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Cuisine focus pills rendered in warm parchment with Herbal Sage text.
      if (menu.cuisineFocus.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          menu.cuisineFocus.forEach { tag ->
            Surface(
              shape = CircleShape,
              color = extendedColors.parchment,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
            ) {
              Text(
                text = savedMenusSanitizeText(tag),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = extendedColors.herbalSage,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Sample dish preview line joined by middle dots.
      if (previewDishes.isNotEmpty()) {
        Text(
          text = previewDishes.joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // 1-tap 48.dp Apply Menu CTA applying the blueprint in 1 O(1) atomic write.
      Button(
        onClick = onApply,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
          containerColor = extendedColors.herbalSage,
          contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
      ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Apply Menu", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      }
    }
  }
}

// Normalizes menu titles, subtitles, and cuisine tags so zero slashes or parentheses appear.
private fun savedMenusSanitizeText(raw: String): String =
  raw
    .replace(" / ", " and ")
    .replace("/", " · ")
    .replace("(", "")
    .replace(")", "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Warm Daylight preview of `SavedMenusSheetContent` with French and Yoruba Family Week #1. */
@Preview(name = "SavedMenusSheet Daylight", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
fun SavedMenusSheetDaylightPreview() {
  // Verify #1 French and Yoruba Family Week ranking and 1-tap Apply Menu button in Daylight theme.
  val mergedRecipesById = mergeHeritageRecipesIntoLibrary(PreviewFixtures.recipes)
    .associateBy { it.recipeId }
  FamilyKitchenBoardTheme(darkTheme = false) {
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
