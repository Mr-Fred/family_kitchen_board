package com.familykitchen.board.ui.navigation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.familykitchen.board.ports.AiRuntimeState
import com.familykitchen.board.ui.TopLevelDestination
import com.familykitchen.board.ui.aichef.formatByteProgressLabel
import com.familykitchen.board.ui.pacer.PacerMode
import com.familykitchen.board.ui.theme.FamilyKitchenBoardTheme
import com.familykitchen.board.ui.theme.KitchenTheme

// Decluttered SavorAI Top App Bar, Compact Region/Vault Pill, Toast Banner, Search Bar,
// 5-Tab Bottom NavigationBar, Wide-Counter NavigationRail, and 2-Pill Plan Mode Switcher.

/**
 * Shared `SavorAI` Top App Bar prioritizing the weighted left brand identity (`Icons.Filled.Restaurant`
 * in a `32.dp` rounded-10 `sageContainer` badge + non-wrapping `"SavorAI"` + `"· <subtitle>"`) and
 * capping the right action cluster at `<= 3` `48.dp` touch controls so `SavorAI` is never crushed
 * to `0.dp` even at `1.35x` Galaxy S22 display/font scaling.
 */
@Composable
fun SavorTopAppBar(
  activeDestination: TopLevelDestination,
  householdName: String,
  marketRegionLabel: String,
  isSearchExpanded: Boolean,
  isDarkTheme: Boolean = false,
  isWideLayout: Boolean = false,
  onOpenQaLab: () -> Unit = {},
  onToggleSearch: () -> Unit = {},
  onToggleDarkTheme: () -> Unit = {},
  onToggleWideLayout: () -> Unit = {},
  onOpenFamilyProfile: () -> Unit = {},
  isBackgroundBusy: Boolean = false,
  isAiSetupExpanded: Boolean = false,
  onToggleAiSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Soft breathing pulse on the local-vault indicator dot confirms offline sync status.
  val pulseTransition = rememberInfiniteTransition(label = "vault_pulse")
  val dotAlpha by pulseTransition.animateFloat(
    initialValue = 0.45f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
    label = "vault_dot_alpha",
  )
  // Shorten market region to single city name so the right cluster never crowds the brand title.
  val compactRegion = compactMarketPillLabel(marketRegionLabel, householdName)

  // Warm linen surface with crisp 1.dp bottom border and zero heavy shadow elevation.
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDarkTheme) 0.96f else 0.94f),
    shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .defaultMinSize(minHeight = 64.dp)
          .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Left: Weighted SavorAI culinary emblem + non-wrapping brand title + muted dot subtitle.
        // Using Modifier.weight(1f) with bounded <= 3 right controls guarantees >= 160.dp on S22.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          // 32.dp rounded-10 botanical sage badge hosting Restaurant icon (zero sparkles).
          Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(extendedColors.sageContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Filled.Restaurant, contentDescription = null, tint = extendedColors.onSageContainer, modifier = Modifier.size(18.dp))
          }
          // Non-wrapping brand wordmark keeps SavorAI readable at 1.35x system font scale.
          Text(
            text = "SavorAI",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
          )
          // Contextual destination subtitle ellipsizes gracefully if viewport width is tight.
          Text(
            text = "· ${activeDestination.headerSubtitle}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // Right cluster capped at <= 3 controls (48.dp touch targets; Dark/Wide live in Profile sheet).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          // Control 1: Compact region / background spinner pill (widthIn max 96.dp -> opens QA Lab).
          Box(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable(onClick = onOpenQaLab),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              border = BorderStroke(1.dp, extendedColors.borderSubtle),
              shadowElevation = 0.dp,
              modifier = Modifier.widthIn(max = 96.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                // Swap the 8.dp fresh botanical dot for a 12.dp spinner during background operations.
                when {
                  isBackgroundBusy -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  else -> Box(
                    modifier = Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(extendedColors.freshIndicator),
                  )
                }
                Text(
                  text = compactRegion,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }

          // Control 2: Contextual 48.dp utility icon (Tune on Chef; Search on Pantry & Recipes).
          when {
            activeDestination == TopLevelDestination.ASK_AI -> {
              IconButton(onClick = onToggleAiSetup, modifier = Modifier.size(48.dp)) {
                Icon(
                  imageVector = Icons.Filled.Tune,
                  contentDescription = "Toggle on-device model setup",
                  tint = if (isAiSetupExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            activeDestination == TopLevelDestination.PANTRY ||
              activeDestination == TopLevelDestination.RECIPES ||
              isSearchExpanded -> {
              IconButton(onClick = onToggleSearch, modifier = Modifier.size(48.dp)) {
                Icon(
                  imageVector = if (isSearchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                  contentDescription = if (isSearchExpanded) "Close search" else "Search pantry and recipes",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          // Control 3: 48.dp Family Profile button opening household goals, templates, & display toggles.
          IconButton(onClick = onOpenFamilyProfile, modifier = Modifier.size(48.dp)) {
            Box(
              modifier = Modifier.size(34.dp).clip(CircleShape).background(extendedColors.sageContainer),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.FamilyRestroom,
                contentDescription = "Open Family Profile and display settings",
                tint = extendedColors.onSageContainer,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
      }
      // Crisp 1.dp warm stone divider separating top bar from scroll canvas without drop shadow.
      HorizontalDivider(thickness = 1.dp, color = extendedColors.borderSubtle)
    }
  }
}

/**
 * Collapsible global search field filtering Cookbook recipes and Pantry items.
 */
@Composable
internal fun SavorExpandableSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onClearAndClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val focusManager = LocalFocusManager.current
  val extendedColors = KitchenTheme.extendedColors
  // Warm parchment search container with 48.dp clear action target.
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(0.5.dp, extendedColors.borderSubtle),
    modifier = modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      placeholder = { Text("Search recipes, cuisines, or pantry items…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      trailingIcon = {
        IconButton(onClick = onClearAndClose, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Close, contentDescription = "Clear search")
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      singleLine = true,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

/**
 * Sticky dismissible status toast banner confirming `O(1)` atomic batch writes and domain gates.
 */
@Composable
internal fun SavorDismissibleToastBanner(
  message: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Botanical sage container frames batch confirmations and non-silent vision gate notices.
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = extendedColors.sageContainer,
    border = BorderStroke(1.dp, extendedColors.herbalSage.copy(alpha = 0.45f)),
    shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = extendedColors.onSageContainer,
        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
      )
      IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss notification", tint = extendedColors.onSageContainer, modifier = Modifier.size(18.dp))
      }
    }
  }
}

/**
 * Cross-tab live progress strip displayed directly beneath [SavorTopAppBar] whenever the user
 * switches away from `Chef` while a Gemma model SAF import or GPU weight load is running.
 *
 * @param runtimeState Active [AiRuntimeState] (`Importing` or `Loading`).
 * @param onOpenAiTab Callback returning the user to `Chef` to view full setup details.
 * @param modifier Optional Compose [Modifier].
 */
@Composable
fun SavorGlobalModelProgressStrip(
  runtimeState: AiRuntimeState,
  onOpenAiTab: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Ignore idle, Ready, or Error states since they do not need a cross-tab progress strip.
  if (runtimeState !is AiRuntimeState.Importing && runtimeState !is AiRuntimeState.Loading) return

  val extendedColors = KitchenTheme.extendedColors
  // Build concise single-line transfer or GPU shader label for cross-tab visibility.
  val summaryText = when (runtimeState) {
    is AiRuntimeState.Importing ->
      "Importing ${runtimeState.fileName} • ${formatByteProgressLabel(runtimeState.copiedBytes, runtimeState.totalBytes)}"
    is AiRuntimeState.Loading ->
      "Initializing ${runtimeState.modelName} on Galaxy GPU…"
    else -> return
  }

  // Tappable global header strip keeps multi-GB background transfers visible across every tab.
  Surface(
    color = extendedColors.sageContainer,
    border = BorderStroke(0.5.dp, extendedColors.herbalSage.copy(alpha = 0.45f)),
    modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(onClick = onOpenAiTab),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = summaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = extendedColors.onSageContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text("View Chef", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
      }
      // Switch between determinate lambda bar (known size) and indeterminate bar (unknown/GPU load).
      when {
        runtimeState is AiRuntimeState.Importing && runtimeState.totalBytes > 0L && runtimeState.copiedBytes > 0L -> {
          val clamped = runtimeState.progressFraction.coerceIn(0f, 1f)
          LinearProgressIndicator(progress = { clamped }, modifier = Modifier.fillMaxWidth())
        }
        else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

/**
 * Centered initial hydration view displayed inside the main viewport while `uiState.isLoading` is
 * `true` so cold starts show an unmistakable spinner instead of flashing an empty pantry or plan.
 */
@Composable
fun SavorVaultLoadingView(modifier: Modifier = Modifier) {
  // Center spinner and status copy so initial snapshot hydration feels calm and intentional.
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(24.dp),
    ) {
      CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
      Text("Loading your SavorAI kitchen vault…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * First-class 5-tab bottom `NavigationBar` (`Chef`, `Plan`, `Pantry` badge, `Recipes`, `Shop` badge)
 * rendered on portrait phones with `tonalElevation = 0.dp` and a crisp `1.dp` warm top border.
 */
@Composable
fun SavorBottomNavigationBar(
  activeDestination: TopLevelDestination,
  groceryBadgeCount: Int,
  expiringSoonCount: Int,
  onSelectDestination: (TopLevelDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Flat warm linen navigation bar framed by a crisp 1.dp top stone divider.
  Column(modifier = modifier.fillMaxWidth()) {
    HorizontalDivider(thickness = 1.dp, color = extendedColors.borderSubtle)
    NavigationBar(containerColor = extendedColors.canvasWarm, tonalElevation = 0.dp, modifier = modifier.fillMaxWidth()) {
      // Render all 5 first-class destinations with non-wrapping labels for S22 1.35x scaling.
      TopLevelDestination.entries.forEach { destination ->
        val isSelected = destination == activeDestination
        val badgeCount = badgeCountForDestination(destination, groceryBadgeCount, expiringSoonCount)
        NavigationBarItem(
          selected = isSelected,
          onClick = { onSelectDestination(destination) },
          icon = { DestinationBadgedIcon(destination = destination, badgeCount = badgeCount) },
          label = {
            Text(
              text = destination.navLabel,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        )
      }
    }
  }
}

/**
 * Leading-edge 5-tab `NavigationRail` rendered automatically in landscape or wide counter mode.
 */
@Composable
fun SavorWideNavigationRail(
  activeDestination: TopLevelDestination,
  groceryBadgeCount: Int,
  expiringSoonCount: Int,
  onSelectDestination: (TopLevelDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Preserves vertical screen height when a Galaxy S22 or tablet rests horizontally on the counter.
  Row(modifier = modifier.fillMaxHeight()) {
    NavigationRail(containerColor = extendedColors.canvasWarm, modifier = Modifier.fillMaxHeight()) {
      Spacer(modifier = Modifier.height(12.dp))
      // Keep rail destination order 1:1 with portrait NavigationBar for muscle-memory consistency.
      TopLevelDestination.entries.forEach { destination ->
        val isSelected = destination == activeDestination
        val badgeCount = badgeCountForDestination(destination, groceryBadgeCount, expiringSoonCount)
        NavigationRailItem(
          selected = isSelected,
          onClick = { onSelectDestination(destination) },
          icon = { DestinationBadgedIcon(destination = destination, badgeCount = badgeCount) },
          label = {
            Text(
              text = destination.navLabel,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          },
          colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
        )
      }
    }
    // Crisp 1.dp warm stone divider separating navigation rail from multi-column counter content.
    VerticalDivider(thickness = 1.dp, color = extendedColors.borderSubtle)
  }
}

/**
 * Clean 2-pill top mode switcher (`"Daily Plan"` vs `"Batch Prep (N)"`) rendered at the top of
 * [TopLevelDestination.PLAN] with `48.dp` touch height, `softWrap = false`, and `1.dp` warm border.
 */
@Composable
fun PlanModeSwitcherBar(
  activeMode: PacerMode,
  batchTaskCount: Int,
  onSelectMode: (PacerMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val extendedColors = KitchenTheme.extendedColors
  // Warm stone segmented track hosting Daily Meal Plan and 4-station Batch Prep without truncation.
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(1.dp, extendedColors.borderSubtle),
    shadowElevation = 0.dp,
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      val options = listOf(
        PacerMode.DAILY_PACER to "Daily Plan",
        PacerMode.WEEKLY_PREP to "Batch Prep ($batchTaskCount)",
      )
      // Render equal-weight 48.dp touch pills so both modes stay legible on S22 1.35x scaling.
      options.forEach { (mode, label) ->
        val isSelected = activeMode == mode
        val icon = if (mode == PacerMode.DAILY_PACER) Icons.Filled.CalendarToday else Icons.Filled.Timer
        Surface(
          shape = CircleShape,
          color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
          border = if (isSelected) BorderStroke(1.dp, extendedColors.borderSubtle) else null,
          shadowElevation = 0.dp,
          modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(CircleShape).clickable { onSelectMode(mode) },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = label,
              style = MaterialTheme.typography.labelLarge,
              color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DestinationBadgedIcon(destination: TopLevelDestination, badgeCount: Int) {
  val icon = iconForDestination(destination)
  // Render coral-terracotta count badge only when unchecked groceries or expiring items exist.
  when {
    badgeCount > 0 -> BadgedBox(
      badge = {
        Badge(
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Text(badgeCount.toString(), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
      },
    ) {
      Icon(imageVector = icon, contentDescription = destination.navLabel)
    }
    else -> Icon(imageVector = icon, contentDescription = destination.navLabel)
  }
}

// Maps each of the 5 primary destinations to a non-sparkle Material 3 icon.
private fun iconForDestination(destination: TopLevelDestination): ImageVector =
  when (destination) {
    TopLevelDestination.ASK_AI -> Icons.Filled.ChatBubbleOutline
    TopLevelDestination.PLAN -> Icons.Filled.CalendarToday
    TopLevelDestination.PANTRY -> Icons.Filled.Kitchen
    TopLevelDestination.RECIPES -> Icons.AutoMirrored.Filled.MenuBook
    TopLevelDestination.GROCERIES -> Icons.Filled.ShoppingCart
  }

// Surface live grocery deficit count on Shop and urgent <= 48h expiry count on Pantry.
private fun badgeCountForDestination(
  destination: TopLevelDestination,
  groceryBadgeCount: Int,
  expiringSoonCount: Int,
): Int =
  when (destination) {
    TopLevelDestination.GROCERIES -> groceryBadgeCount
    TopLevelDestination.PANTRY -> expiringSoonCount
    TopLevelDestination.ASK_AI, TopLevelDestination.PLAN, TopLevelDestination.RECIPES -> 0
  }

// Extracts concise single-word/city region pill copy ("Antipolo") so right bar controls stay <= 96.dp.
private fun compactMarketPillLabel(marketRegionLabel: String, householdName: String): String {
  val shortMarket = marketRegionLabel.substringBefore(',').substringBefore('&').substringBefore('•').trim()
  return when {
    shortMarket.isNotEmpty() -> shortMarket
    householdName.isNotBlank() -> householdName.trim()
    else -> "Kitchen"
  }
}

@Preview(name = "Top Bar & 5-Tab Nav — Chef Daylight", showBackground = true, widthDp = 360)
@Composable
private fun SavorBarsPreview() {
  // Verifies non-crushed SavorAI header and 5-tab bar on narrow 360dp portrait width.
  FamilyKitchenBoardTheme {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SavorTopAppBar(
        activeDestination = TopLevelDestination.ASK_AI,
        householdName = "Our Family Kitchen",
        marketRegionLabel = "Antipolo & Marikina, PH",
        isSearchExpanded = false,
      )
      PlanModeSwitcherBar(activeMode = PacerMode.DAILY_PACER, batchTaskCount = 4, onSelectMode = {})
      SavorBottomNavigationBar(
        activeDestination = TopLevelDestination.PLAN,
        groceryBadgeCount = 6,
        expiringSoonCount = 3,
        onSelectDestination = {},
      )
    }
  }
}
