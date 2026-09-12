package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates hierarchical habit deletion while keeping confirmation state screen-independent. */
class HabitDeletionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository
) {
    private val _pendingDeletion = MutableStateFlow<PendingHabitDeletion?>(null)
    val pendingDeletion: StateFlow<PendingHabitDeletion?> = _pendingDeletion.asStateFlow()

    suspend fun requestDeletion(habit: HabitEntity) {
        val children = habitRepository.getHabitChildren(habit.uuid)
        if (children.isEmpty()) {
            habitRepository.deleteHabit(habit, context)
        } else {
            _pendingDeletion.value = PendingHabitDeletion(habit, children.size)
        }
    }

    suspend fun deleteWithChildren() {
        val pending = _pendingDeletion.value ?: return
        habitRepository.deleteHabitWithChildren(pending.habit, context)
        _pendingDeletion.value = null
    }

    suspend fun deleteKeepingChildren() {
        val pending = _pendingDeletion.value ?: return
        habitRepository.deleteHabitOrphanChildren(pending.habit, context)
        _pendingDeletion.value = null
    }

    fun dismissDeletion() {
        _pendingDeletion.value = null
    }
}

data class PendingHabitDeletion(
    val habit: HabitEntity,
    val childCount: Int
)
