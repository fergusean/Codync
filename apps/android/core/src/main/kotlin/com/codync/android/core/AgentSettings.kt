package com.codync.android.core

import kotlinx.serialization.Serializable

@Serializable data class AgentModel(val id: String, val name: String, val description: String? = null)
@Serializable data class AgentModels(val models: List<AgentModel>, val currentModelId: String? = null) {
    val agentDefault: AgentModel? get() = models.firstOrNull { it.name.lowercase().startsWith("default") }
    fun options(selected: String?): List<ModelChoice> {
        val default = agentDefault
        val available = models.filter { it.id != default?.id }
        val current = available.firstOrNull { it.id == currentModelId }
        val choices = mutableListOf(ModelChoice(null, default?.name ?: current?.let { "Default · ${it.name}" } ?: "Default", default?.description))
        choices += available.map { ModelChoice(it.id, it.name, it.description) }
        if (!selected.isNullOrBlank() && selected != default?.id && available.none { it.id == selected }) choices += ModelChoice(selected, selected)
        return choices
    }
    fun normalize(selected: String?): String? = selected?.takeIf { it.isNotBlank() && it != agentDefault?.id }
}
data class ModelChoice(val id: String?, val name: String, val description: String? = null)

@Serializable data class DirListing(val path: String, val parent: String? = null, val isGit: Boolean = false, val dirs: List<DirItem>)
@Serializable data class DirItem(val name: String, val path: String, val isGit: Boolean = false)
