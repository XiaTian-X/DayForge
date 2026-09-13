package com.dayforge.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dayforge.R
import com.dayforge.data.local.entity.HabitEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHabitSelector(
    parentHabitName: String?,
    topLevelHabits: List<HabitEntity>,
    currentHabitId: Long,
    showSelector: Boolean,
    onToggleSelector: () -> Unit,
    onSelectParent: (HabitEntity) -> Unit,
    onClearParent: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = parentHabitName ?: stringResource(R.string.edit_habit_none_top_level),
            onValueChange = {},
            readOnly = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.edit_habit_parent_habit_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // "None" option for top-level habit
            DropdownMenuItem(
                text = { androidx.compose.material3.Text(stringResource(R.string.edit_habit_none_top_level)) },
                onClick = {
                    onClearParent()
                    expanded = false
                }
            )
            // List of top-level habits (excluding current habit to prevent self-reference)
            topLevelHabits.filter { it.id != currentHabitId }.forEach { habit ->
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text(habit.name) },
                    onClick = {
                        onSelectParent(habit)
                        expanded = false
                    }
                )
            }
        }
    }
}
