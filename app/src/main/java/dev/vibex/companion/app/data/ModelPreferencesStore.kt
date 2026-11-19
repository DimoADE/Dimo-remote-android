package dev.vibex.companion.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class ModelPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("vibex_model_prefs", Context.MODE_PRIVATE)

    private val _favoriteIds = MutableStateFlow(loadSet(KEY_FAVORITES))
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    private val _hiddenIds = MutableStateFlow(loadSet(KEY_HIDDEN))
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds

    private val _customModelsByAgent = MutableStateFlow(loadCustomMap())
    val customModelsByAgent: StateFlow<Map<String, List<String>>> = _customModelsByAgent

    fun toggleFavorite(modelId: String) {
        val next = _favoriteIds.value.toMutableSet().apply {
            if (!add(modelId)) remove(modelId)
        }
        _favoriteIds.value = next
        saveSet(KEY_FAVORITES, next)
    }

    fun toggleHidden(modelId: String) {
        val next = _hiddenIds.value.toMutableSet().apply {
            if (!add(modelId)) remove(modelId)
        }
        _hiddenIds.value = next
        saveSet(KEY_HIDDEN, next)
    }

    fun addCustomModel(agentId: String, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        _customModelsByAgent.update { current ->
            val updated = (listOf(trimmed) + current[agentId].orEmpty()).distinct()
            val next = current + (agentId to updated)
            saveCustomMap(next)
            next
        }
    }

    private fun loadSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()).orEmpty().filter { it.isNotBlank() }.toSet()

    private fun saveSet(key: String, values: Set<String>) {
        prefs.edit().putStringSet(key, values).apply()
    }

    private fun loadCustomMap(): Map<String, List<String>> {
        val raw = prefs.getString(KEY_CUSTOM, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0] to parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                } else null
            }
            .toMap()
    }

    private fun saveCustomMap(map: Map<String, List<String>>) {
        val encoded = map.entries
            .filter { it.key.isNotBlank() && it.value.isNotEmpty() }
            .joinToString("\n") { (agent, models) -> "$agent\t${models.joinToString(",")}" }
        prefs.edit().putString(KEY_CUSTOM, encoded).apply()
    }

    companion object {
        private const val KEY_FAVORITES = "vibex.favoriteModelIDs"
        private const val KEY_HIDDEN = "vibex.hiddenModelIDs"
        private const val KEY_CUSTOM = "vibex.customModelsByAgent"
    }
}
