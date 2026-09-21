package com.opencode.remote.ui.chat

import androidx.compose.runtime.Immutable
import com.opencode.remote.data.api.dto.AgentInfo

/**
 * Lightweight model pointer — uniquely identifies a model across providers.
 */
data class ModelSelectionRef(
    val providerId: String,
    val modelId: String,
)

/**
 * A model option available for selection, with display metadata.
 */
data class ModelSelectionOption(
    val ref: ModelSelectionRef,
    val providerName: String,
    val modelName: String,
    val variants: List<String> = emptyList(),
) {
    val displayLabel: String get() = "$providerName / $modelName"
}

/**
 * Committed or draft selection configuration.
 * Null means "auto" (server default).
 */
data class ChatSelectionConfig(
    val agent: String? = null,
    val model: ModelSelectionRef? = null,
    val variant: String? = null,
)

/**
 * Full UI state for the chat selection settings dialog.
 * Uses committed/draft separation: draft is edited in the dialog,
 * committed is the active selection.
 */
@Immutable
data class ChatSelectionUiState(
    val isDialogOpen: Boolean = false,
    val availableAgents: List<AgentInfo> = emptyList(),
    val availableModels: List<ModelSelectionOption> = emptyList(),
    val committed: ChatSelectionConfig = ChatSelectionConfig(),
    val draft: ChatSelectionConfig = ChatSelectionConfig(),
    /** 服务端生效默认值（auto 时显示真名用，不参与提交语义）。 */
    val resolvedDefaultAgent: String? = null,
    val resolvedDefaultModel: ModelSelectionRef? = null,
) {
    /** Variants available for the currently drafted model. */
    val draftVariants: List<String>
        get() = resolveModel(draft.model)?.variants.orEmpty()

    /** Whether any non-default selection has been made. */
    val hasExplicitOverrides: Boolean
        get() = committed.agent != null || committed.model != null || committed.variant != null

    /** Resolve a model reference to its full option, or null if not found. */
    fun resolveModel(ref: ModelSelectionRef?): ModelSelectionOption? =
        ref?.let { target ->
            availableModels.firstOrNull { opt ->
                opt.ref.providerId == target.providerId && opt.ref.modelId == target.modelId
            }
        }
}
