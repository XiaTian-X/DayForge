package com.dayforge.data.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.dayforge.data.appearance.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before

/** Reuses the existing physical business DB fixture; adds only real account material storage. */
abstract class NextObjectEditorFixture : NextCoreRequestFixture() {
    // The inherited DB and any Activity rules have the default -1 order; -2 stays outside both.
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    internal lateinit var iconDatabase: AccountIconDatabase
    internal lateinit var iconMetadata: AccountIconRepository
    internal lateinit var icons: AccountIconController
    internal lateinit var editor: NextObjectEditor
    internal lateinit var creator: NextObjectCreator
    private val models = ViewModelStore()
    private val ownedModels = mutableListOf<ViewModel>()

    @Before fun openEditor() {
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        iconDatabase = AccountIconDatabase.open(app)
        iconMetadata = AccountIconRepository(iconDatabase, tokens, sessions)
        val store = AccountIconStore(iconMetadata, AccountIconFiles(directory))
        val imports = AccountIconImport(iconMetadata, store, AccountIconTransfers(iconDatabase, iconMetadata, store))
        icons = AccountIconController({ AccountIconRuntime(iconMetadata, store,
            AccountIconRenderer(iconMetadata, store), imports,
            AccountIconDocuments(imports, app.contentResolver), iconDatabase::close) }, tokens)
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    internal fun editingHabits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor)
    internal fun editingMetrics() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
        db.habitMetricLinkDao(), nextObjectEditor = editor)
    internal fun creatingHabits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, nextObjectCreator = creator)
    internal fun creatingMetrics() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
        db.habitMetricLinkDao(), nextObjectEditor = editor, nextObjectCreator = creator)
    internal fun onceRepository() = OneTimeRepository(db, tokens, sessions, preferences)
    internal fun onceHabits(once: OneTimeRepository) = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, nextObjectCreator = creator, oneTimeRepository = once)
    internal fun <T : ViewModel> own(model: T): T {
        models.put("editor:${ownedModels.size}", model); ownedModels += model; return model
    }

    @After fun closeEditor() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { models.clear() }
        for (model in ownedModels) withTimeout(5000) { model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
        if (::iconDatabase.isInitialized) {
            iconDatabase.close()
            assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
        }
    }
}
