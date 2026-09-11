package com.dayforge.ui.screens.nested

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.LinkedMetricSnapshot
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.MetricValueDraft
import com.dayforge.domain.service.TimerService
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Coordinates linked-metric presentation behavior for the nested habit screen. */
class NestedMetricCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository
) {
    val pendingMetricHabits: Flow<Set<Long>> = preferencesManager.pendingMetricHabits

    private val _postCheckInState = MutableStateFlow<PostCheckInState?>(null)
    val postCheckInState: StateFlow<PostCheckInState?> = _postCheckInState

    /**
     * Observe card metrics without per-habit queries. The Room projection also makes changes made
     * by sync visible when links, metric metadata, or metric logs change.
     */
    fun observeLinkedMetrics(
        habits: Flow<List<HabitEntity>>
    ): Flow<Map<Long, List<LinkedMetricInfo>>> = combine(
        habits,
        metricRepository.observeLinkedMetricSnapshots()
    ) { currentHabits, snapshots ->
        val currentHabitIds = currentHabits.asSequence().map { it.id }.toSet()
        snapshots
            .asSequence()
            .filter { it.habitId in currentHabitIds }
            .groupBy(LinkedMetricSnapshot::habitId) { it.toLinkedMetricInfo() }
    }.catch { error ->
        Log.e(TAG, "Failed to observe linked metrics", error)
        emit(emptyMap())
    }

    suspend fun showPromptIfNeeded(habitId: Long, habitName: String) {
        if (preferencesManager.getNeverAskAgain(habitId).first()) return

        val metricInfos = try {
            metricRepository.getLinkedMetricSnapshots(habitId)
                .asSequence()
                .filter(LinkedMetricSnapshot::promptOnComplete)
                .map { it.toLinkedMetricInfo() }
                .toList()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to load linked metrics for habit $habitId", error)
            emptyList()
        }

        if (metricInfos.isNotEmpty()) {
            _postCheckInState.value = PostCheckInState(
                habitId = habitId,
                habitName = habitName,
                linkedMetrics = metricInfos,
                show = true
            )
        }
    }

    suspend fun recordMetricValues(habitId: Long, values: List<MetricValueInput>): Boolean {
        return try {
            metricRepository.recordValues(
                values.map { input -> MetricValueDraft(input.metricId, input.value, input.note) },
                recordedAt = System.currentTimeMillis()
            )
            preferencesManager.removePendingMetricHabit(habitId)
            context.sendBroadcast(Intent(TimerService.ACTION_WIDGET_UPDATE).apply {
                putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                setPackage(context.packageName)
            })
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to record linked metrics", error)
            Toast.makeText(
                context,
                context.getString(R.string.metric_error_record_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        preferencesManager.setNeverAskAgain(habitId, value)
    }

    fun dismissPrompt() {
        _postCheckInState.value = null
    }

    private fun LinkedMetricSnapshot.toLinkedMetricInfo() = LinkedMetricInfo(
        metricName = metricName,
        metricId = metricId,
        latestValue = latestValue,
        unit = unit,
        decimalPlaces = decimalPlaces
    )

    private companion object {
        const val TAG = "NestedMetricCoordinator"
    }
}
