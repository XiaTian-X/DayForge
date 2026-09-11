package com.dayforge.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ActivityIndicator component displays habit activity rate with flame icon.
 *
 * Display format: 🔥 activityRate%
 *
 * Uses Material Icons Rounded.LocalFireDepartment for flame icon
 * with colorScheme.tertiary for accent color.
 *
 * @param activityRate Activity rate 0-100
 * @param textColor Optional text color override
 * @param textSize Optional text size override
 * @param modifier Modifier for custom styling
 */
@Composable
fun StreakIndicator(
    activityRate: Int,
    textColor: Color? = null,
    textSize: TextUnit? = null,
    modifier: Modifier = Modifier
) {
    val resolvedColor = textColor ?: MaterialTheme.colorScheme.onSurface
    val resolvedTextSize = textSize ?: 14.sp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // Flame icon with accent color
        Icon(
            imageVector = Icons.Rounded.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(16.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Activity rate percentage
        Text(
            text = "$activityRate%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = resolvedTextSize),
            color = resolvedColor
        )
    }
}
