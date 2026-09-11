package com.dayforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Data class representing an item in the SpeedDialFAB.
 *
 * @param label Text label displayed next to the icon
 * @param icon ImageVector icon for the item
 * @param onClick Callback when the item is clicked
 */
data class SpeedDialItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/**
 * An expandable FloatingActionButton that shows multiple action options.
 *
 * Per D-01: Shows multiple action options on tap
 * Per D-02: Icon changes to close when expanded
 * Per D-03: Reuses existing FAB position
 *
 * @param isExpanded Whether the FAB is currently expanded
 * @param onToggle Callback to toggle the expanded state
 * @param items List of SpeedDialItems to display when expanded
 * @param modifier Optional modifier
 */
@Composable
fun SpeedDialFAB(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    items: List<SpeedDialItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        // Animated items appear above the FAB
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                items.forEach { item ->
                    SpeedDialItemCard(item = item)
                }
                // Extra spacing between menu items and FAB
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Main FAB
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondary
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (isExpanded) "Close" else "Add"
            )
        }
    }
}

/**
 * Individual card item in the SpeedDialFAB.
 *
 * @param item The SpeedDialItem data
 */
@Composable
private fun SpeedDialItemCard(item: SpeedDialItem) {
    Card(
        modifier = Modifier.clickable { item.onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}