package com.dayforge.ui.screens.creategoal

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable
data class CreateGoalUiState(
    val name: String = "",
    val description: String = "",
    val iconResId: Int = 0,
    val colorHex: String = "#2196F3",
    val targetCycles: Int? = null,
    val failMode: FailMode = FailMode.STRICT,
    val parentUuid: String = UUID.randomUUID().toString(),
    val children: List<HabitDraft> = emptyList(),
    val appearance: com.dayforge.domain.model.ObjectAppearance? = null,
    val creationScope: String? = null,
    @Transient val creationAuthority: com.dayforge.data.repository.ObjectCreationAuthority? = null,
    @Transient val isSaving: Boolean = false,
    @Transient val savedGoalId: Long? = null,
    @Transient val showIconPicker: Boolean = false,
    @Transient val showColorPicker: Boolean = false,
    @Transient val errorMessage: String? = null
) {
    val isValid: Boolean get() = name.isNotBlank() && name.length <= 50
}

@HiltViewModel
class CreateGoalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        savedStateHandle.get<String>(DRAFT_KEY)?.let { Json.decodeFromString<CreateGoalUiState>(it) }
            ?: CreateGoalUiState()
    )
    val uiState = _uiState.asStateFlow()

    private fun update(state: CreateGoalUiState) {
        savedStateHandle[DRAFT_KEY] = Json.encodeToString(state)
        _uiState.value = state
    }

    init { update(_uiState.value) }

    fun beginCreation(authority: com.dayforge.data.repository.ObjectCreationAuthority) {
        val current = _uiState.value
        if (current.creationAuthority === authority) return
        if (current.creationAuthority != null || current.isSaving || current.savedGoalId != null ||
            (current.creationScope != null && current.creationScope != authority.scopeKey) ||
            current.children.any { it.appearance == null }) {
            update(current.copy(errorMessage = context.getString(R.string.toast_save_failed, "OBJECT_CREATE_DRAFT_EXPIRED")))
            return
        }
        update(current.copy(creationAuthority = authority, creationScope = authority.scopeKey,
            appearance = current.appearance ?: com.dayforge.domain.model.ObjectAppearance(
                com.dayforge.domain.model.IconReference.Role("goal.default"), current.colorHex, "theme")))
    }

    fun updateAppearance(value: com.dayforge.domain.model.ObjectAppearance) {
        if (_uiState.value.creationAuthority == null) return
        update(_uiState.value.copy(appearance = value, colorHex = value.accentColor, showIconPicker = false))
    }

    fun updateName(name: String) = update(_uiState.value.copy(name = name))
    fun updateDescription(description: String) = update(_uiState.value.copy(description = description))
    fun updateIcon(iconResId: Int) { if (_uiState.value.appearance == null) update(_uiState.value.copy(iconResId = iconResId)) }
    fun updateColor(colorHex: String) = update(_uiState.value.copy(colorHex = colorHex,
        appearance = _uiState.value.appearance?.copy(accentColor = colorHex)))
    fun updateTargetCycles(value: Int?) = update(_uiState.value.copy(targetCycles = value))
    fun updateFailMode(mode: FailMode) = update(_uiState.value.copy(failMode = mode))
    fun toggleIconPicker() = update(_uiState.value.copy(showIconPicker = !_uiState.value.showIconPicker))
    fun toggleColorPicker() = update(_uiState.value.copy(showColorPicker = !_uiState.value.showColorPicker))

    fun addChildHabit(draft: HabitDraft) {
        check(!_uiState.value.isSaving)
        require(draft.habitType != HabitType.GOAL)
        require((draft.appearance == null) == (_uiState.value.appearance == null)) { "OBJECT_CREATE_DRAFT_PROTOCOL_MISMATCH" }
        update(_uiState.value.copy(children = _uiState.value.children.filterNot { it.id == draft.id } + draft))
    }

    fun removeChildHabit(draftId: String) {
        if (_uiState.value.isSaving) return
        update(_uiState.value.copy(children = _uiState.value.children.filterNot { it.id == draftId }))
    }

    fun saveGoal() {
        val state = _uiState.value
        if (!state.isValid || state.isSaving || state.savedGoalId != null) return
        update(state.copy(isSaving = true, errorMessage = null))
        viewModelScope.launch {
            try {
                val goal = HabitDraft(id = state.parentUuid, name = state.name, description = state.description,
                    habitType = HabitType.GOAL, iconResId = state.iconResId, colorHex = state.colorHex,
                    targetCycles = state.targetCycles, failMode = state.failMode, appearance = state.appearance)
                val id = habitRepository.createGoal(goal, state.children, context, state.creationAuthority)
                update(_uiState.value.copy(isSaving = false, savedGoalId = id))
            } catch (e: CancellationException) {
                update(_uiState.value.copy(isSaving = false))
                throw e
            } catch (e: Exception) {
                update(_uiState.value.copy(isSaving = false,
                    errorMessage = context.getString(R.string.toast_save_failed, e.message)))
            }
        }
    }

    fun clearSavedGoal() = update(_uiState.value.copy(savedGoalId = null))
    fun clearError() = update(_uiState.value.copy(errorMessage = null))

    companion object { internal const val DRAFT_KEY = "goal_draft_v1" }
}
