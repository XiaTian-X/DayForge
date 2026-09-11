package com.dayforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayforge.R
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.util.DateTimeUtils
import java.util.Calendar

/**
 * CompletionCalendar displays a traditional monthly calendar view with navigation.
 *
 * Features:
 * - Shows one month at a time with day cells in a 7-column grid (Sun-Sat)
 * - Left/right arrow buttons to navigate between months
 * - Tap "YYYY年M月" header to open a simple year-month picker dialog
 * - Color-coded cells: completed days filled with primary color, missed days in surfaceVariant
 * - Today highlighted with a primary-colored border
 */
@Composable
fun CompletionCalendar(
    completions: List<CompletionEntity>,
    modifier: Modifier = Modifier,
    title: String? = null,
    initialMonth: Long? = null
) {
    val cellSpacing = 4.dp
    val completedDates = completions.map { DateTimeUtils.normalizeToDay(it.date) }.toSet()

    val todayCal = Calendar.getInstance()
    todayCal.timeInMillis = DateTimeUtils.startOfDayMillis()
    val initialYear = initialMonth?.let {
        val cal = Calendar.getInstance()
        cal.timeInMillis = it
        cal.get(Calendar.YEAR)
    } ?: todayCal.get(Calendar.YEAR)
    val initialMonthNum = initialMonth?.let {
        val cal = Calendar.getInstance()
        cal.timeInMillis = it
        cal.get(Calendar.MONTH)
    } ?: todayCal.get(Calendar.MONTH)

    var displayedYear by remember { mutableStateOf(initialYear) }
    var displayedMonth by remember { mutableStateOf(initialMonthNum) }
    val today = DateTimeUtils.startOfDayMillis()

    // Picker state
    var showPicker by remember { mutableStateOf(false) }

    // Calendar grid calculation
    val calendar = Calendar.getInstance()
    calendar.set(displayedYear, displayedMonth, 1)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    calendar.set(displayedYear, displayedMonth + 1, 0)
    val daysInMonth = calendar.get(Calendar.DAY_OF_MONTH)
    val daysBeforeMonth = firstDayOfWeek - Calendar.SUNDAY
    val numWeeks = (daysBeforeMonth + daysInMonth + 6) / 7

    val dayLabels = listOf(
        stringResource(R.string.calendar_day_sun),
        stringResource(R.string.calendar_day_mon),
        stringResource(R.string.calendar_day_tue),
        stringResource(R.string.calendar_day_wed),
        stringResource(R.string.calendar_day_thu),
        stringResource(R.string.calendar_day_fri),
        stringResource(R.string.calendar_day_sat)
    )

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Navigation header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (displayedMonth == 0) { displayedMonth = 11; displayedYear-- } else { displayedMonth-- }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.calendar_prev_month)
                )
            }

            // Clickable date header: "YYYY年M月"
            Text(
                text = stringResource(R.string.calendar_header_format, displayedYear, displayedMonth + 1),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showPicker = true }
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = {
                    if (displayedMonth == 11) { displayedMonth = 0; displayedYear++ } else { displayedMonth++ }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.calendar_next_month)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Day of week labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(cellSpacing)
        ) {
            for (label in dayLabels) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Calendar grid
        for (week in 0 until numWeeks) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(cellSpacing)
            ) {
                for (dayInWeek in 0 until 7) {
                    val cellIndex = week * 7 + dayInWeek
                    val dayOfMonth = cellIndex - daysBeforeMonth + 1

                    if (dayOfMonth < 1 || dayOfMonth > daysInMonth) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val cellCal = Calendar.getInstance()
                        cellCal.set(displayedYear, displayedMonth, dayOfMonth)
                        val cellDate = DateTimeUtils.normalizeToDay(cellCal.timeInMillis)

                        val hasCompletion = cellDate in completedDates
                        val isFuture = cellDate > today
                        val isToday = cellDate == today

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .border(
                                    width = if (isToday) 2.dp else 1.dp,
                                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = MaterialTheme.shapes.small
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isFuture -> {
                                    Text(
                                        text = dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        fontSize = 12.sp
                                    )
                                }
                                hasCompletion -> {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                shape = MaterialTheme.shapes.small
                                            )
                                    )
                                    Text(
                                        text = dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                shape = MaterialTheme.shapes.small
                                            )
                                    )
                                    Text(
                                        text = dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (week < numWeeks - 1) {
                Spacer(modifier = Modifier.height(cellSpacing))
            }
        }
    }

    // Simple year-month picker dialog with +/- buttons
    if (showPicker) {
        val currentYear = todayCal.get(Calendar.YEAR)
        val minYear = currentYear - 5
        val maxYear = currentYear + 1

        var pickerYear by remember { mutableIntStateOf(displayedYear) }
        var pickerMonth by remember { mutableIntStateOf(displayedMonth + 1) } // 1-12 for display

        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = {
                Text(
                    text = stringResource(R.string.calendar_select_date),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Year picker row
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (pickerYear > minYear) pickerYear-- }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                        Text(
                            text = stringResource(R.string.calendar_picker_year_format, pickerYear),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        IconButton(
                            onClick = { if (pickerYear < maxYear) pickerYear++ }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Month picker row
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (pickerMonth > 1) pickerMonth-- }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                        Text(
                            text = stringResource(R.string.calendar_picker_month_format, pickerMonth),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        IconButton(
                            onClick = { if (pickerMonth < 12) pickerMonth++ }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    displayedYear = pickerYear
                    displayedMonth = pickerMonth - 1 // Convert back to 0-11
                    showPicker = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}