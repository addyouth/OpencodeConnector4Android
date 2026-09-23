package com.opencode.remote.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opencode.remote.data.api.dto.ServerInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_prefs")

private const val ENCRYPTED_PREFS_NAME = "connection_encrypted_prefs"
private const val KEY_ENCRYPTED_PASSWORD = "encrypted_password"

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 4096,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val insecureTrust: Boolean = false,
    val autoReconnect: Boolean = true,
    val serverId: String? = null,
)

data class StoredModelSelection(
    val providerId: String,
    val modelId: String,
) {
    fun serialize(): String = "$providerId:$modelId"

    companion object {
        fun deserialize(value: String): StoredModelSelection? {
            val colonIndex = value.indexOf(':')
            if (colonIndex <= 0) return null
            return StoredModelSelection(
                providerId = value.substring(0, colonIndex),
                modelId = value.substring(colonIndex + 1),
            )
        }
    }
}

@Singleton
class ConnectionPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverManager: ServerManager,
) {
    companion object {
        private const val TAG = "ConnectionPreferences"
    }

    private object Keys {
        val HOST = stringPreferencesKey("connection_host")
        val PORT = intPreferencesKey("connection_port")
        val USERNAME = stringPreferencesKey("connection_username")
        val PASSWORD = stringPreferencesKey("connection_password")
        val USE_TLS = booleanPreferencesKey("connection_use_tls")
        val INSECURE_TRUST = booleanPreferencesKey("connection_insecure_trust")
        val AUTO_RECONNECT = booleanPreferencesKey("connection_auto_reconnect")
        val LANGUAGE = stringPreferencesKey("app_language")
        val DARK_MODE = booleanPreferencesKey("app_dark_mode")
        val HIDE_CHILD_SESSIONS = booleanPreferencesKey("hide_child_sessions")
        val LIST_DENSITY = stringPreferencesKey("list_density")
        val PINNED_SESSION_ID = stringPreferencesKey("pinned_session_id")
        val PINNED_SESSION_DIR = stringPreferencesKey("pinned_session_dir")
        val AUTO_DIRECT_PIN = booleanPreferencesKey("auto_direct_pinned")
        val CUSTOM_TEMPLATES = stringPreferencesKey("custom_templates")
        val PINNED_SESSIONS = stringPreferencesKey("pinned_sessions")
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences (keystore may be invalidated)", e)
            null
        }
    }

    private fun readEncryptedPassword(): String {
        return try {
            encryptedPrefs?.getString(KEY_ENCRYPTED_PASSWORD, null) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read encrypted password", e)
            ""
        }
    }

    private fun writeEncryptedPassword(password: String) {
        try {
            encryptedPrefs?.edit()?.putString(KEY_ENCRYPTED_PASSWORD, password)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write encrypted password", e)
        }
    }

    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data
        .map { prefs ->
            val encryptedPassword = readEncryptedPassword()
            val plaintextPassword = prefs[Keys.PASSWORD] ?: ""

            // Migration: if encrypted is empty but plaintext exists, migrate and clear plaintext
            val password = if (encryptedPassword.isEmpty() && plaintextPassword.isNotEmpty()) {
                writeEncryptedPassword(plaintextPassword)
                try {
                    // Best-effort removal of plaintext from DataStore
                    // (we can't edit inside a read map, so we fire-and-forget via a coroutine)
                    // The password will be cleared on the next saveConfig() call
                } catch (_: Exception) { }
                plaintextPassword
            } else {
                encryptedPassword
            }

            ConnectionConfig(
                host = prefs[Keys.HOST] ?: "",
                port = prefs[Keys.PORT] ?: 4096,
                username = prefs[Keys.USERNAME] ?: "",
                password = password,
                useTls = prefs[Keys.USE_TLS] ?: false,
                insecureTrust = prefs[Keys.INSECURE_TRUST] ?: false,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            )
        }
        .catch { e ->
            Log.e(TAG, "Failed to read connection config", e)
            emit(ConnectionConfig())
        }

    val language: Flow<String> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.LANGUAGE] ?: "zh"
        }
        .catch { e ->
            Log.e(TAG, "Failed to read language", e)
            emit("zh")
        }

    val darkMode: Flow<Boolean> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.DARK_MODE] ?: false
        }
        .catch { e ->
            Log.e(TAG, "Failed to read dark mode", e)
            emit(false)
        }

    val hideChildSessions: Flow<Boolean> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.HIDE_CHILD_SESSIONS] ?: false
        }
        .catch { e ->
            Log.e(TAG, "Failed to read hide child sessions", e)
            emit(false)
        }

    suspend fun saveLanguage(lang: String) {
        try {
            context.dataStore.edit { it[Keys.LANGUAGE] = lang }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save language", e)
        }
    }

    suspend fun saveDarkMode(enabled: Boolean) {
        try {
            context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dark mode", e)
        }
    }

    suspend fun saveHideChildSessions(enabled: Boolean) {
        try {
            context.dataStore.edit { it[Keys.HIDE_CHILD_SESSIONS] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save hide child sessions", e)
        }
    }

    /** 会话置顶：可多个（Vault 主会话 + 写作等），直达用第一个（单全局，不按服务器分）。 */
    val pinnedSessions: Flow<List<PinnedEntry>> = context.dataStore.data
        .map { prefs ->
            val raw = prefs[Keys.PINNED_SESSIONS]
            if (raw != null) {
                try {
                    return@map json.decodeFromString(ListSerializer(PinnedEntry.serializer()), raw).take(5)
                } catch (_: Exception) {}
            }
            // 迁移 1.4.42 单置顶
            val oldId = prefs[Keys.PINNED_SESSION_ID]
            if (oldId != null) listOf(PinnedEntry(oldId, prefs[Keys.PINNED_SESSION_DIR]))
            else emptyList()
        }
        .catch { e -> Log.e(TAG, "Failed to read pins", e); emit(emptyList()) }

    suspend fun savePinned(pins: List<PinnedEntry>) {
        try {
            val encoded = json.encodeToString(ListSerializer(PinnedEntry.serializer()), pins.take(5))
            context.dataStore.edit {
                it[Keys.PINNED_SESSIONS] = encoded
                it.remove(Keys.PINNED_SESSION_ID)
                it.remove(Keys.PINNED_SESSION_DIR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pins", e)
        }
    }

    val autoDirectPin: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.AUTO_DIRECT_PIN] ?: false }
        .catch { e -> Log.e(TAG, "Failed to read auto direct", e); emit(false) }

    suspend fun saveAutoDirectPin(enabled: Boolean) {
        try {
            context.dataStore.edit { it[Keys.AUTO_DIRECT_PIN] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save auto direct", e)
        }
    }

    /** 快捷模板 Tier 1：标题（chip 显示）与内容（实际发送）可不同；预设常驻不可删。 */
    val customTemplates: Flow<List<TemplateEntry>> = context.dataStore.data
        .map { prefs ->
            val raw = prefs[Keys.CUSTOM_TEMPLATES] ?: return@map emptyList<TemplateEntry>()
            try {
                json.decodeFromString(ListSerializer(TemplateEntry.serializer()), raw).take(10)
            } catch (_: Exception) {
                // 1.4.42 空串分隔的按字切脏数据直接丢弃
                emptyList()
            }
        }
        .catch { e -> Log.e(TAG, "Failed to read templates", e); emit(emptyList()) }

    suspend fun saveCustomTemplates(templates: List<TemplateEntry>) {
        try {
            val encoded = json.encodeToString(ListSerializer(TemplateEntry.serializer()), templates.take(10))
            context.dataStore.edit { it[Keys.CUSTOM_TEMPLATES] = encoded }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save templates", e)
        }
    }

    val listDensity: Flow<String> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.LIST_DENSITY] ?: "default"
        }
        .catch { e ->
            Log.e(TAG, "Failed to read list density", e)
            emit("default")
        }

    suspend fun saveListDensity(density: String) {
        try {
            context.dataStore.edit { it[Keys.LIST_DENSITY] = density }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save list density", e)
        }
    }

    suspend fun saveHost(host: String) {
        try {
            context.dataStore.edit { it[Keys.HOST] = host }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save host", e)
        }
    }

    suspend fun savePort(port: Int) {
        try {
            context.dataStore.edit { it[Keys.PORT] = port }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save port", e)
        }
    }

    suspend fun saveConfig(config: ConnectionConfig) {
        try {
            // Write password to encrypted prefs only
            writeEncryptedPassword(config.password)

            // Write all other fields to DataStore (password removed from plaintext)
            context.dataStore.edit {
                it[Keys.HOST] = config.host
                it[Keys.PORT] = config.port
                it[Keys.USERNAME] = config.username
                it.remove(Keys.PASSWORD) // Ensure plaintext password is cleared
                it[Keys.USE_TLS] = config.useTls
                it[Keys.INSECURE_TRUST] = config.insecureTrust
                it[Keys.AUTO_RECONNECT] = config.autoReconnect
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    // --- Multi-server delegation to ServerManager ---

    val savedServers: Flow<List<ServerInfo>> = serverManager.servers

    suspend fun addServer(info: ServerInfo, password: String) =
        serverManager.addServer(info, password)

    suspend fun deleteServer(id: String) =
        serverManager.deleteServer(id)

    fun getServerPassword(id: String): String? =
        serverManager.getPassword(id)

    val lastActiveServerId: Flow<String?> = serverManager.lastActiveServerId

    suspend fun saveLastActiveServerId(id: String) =
        serverManager.saveLastActiveServerId(id)

    // --- Per-session selection persistence ---

    suspend fun saveSelectedAgent(sessionId: String, agent: String?) {
        try {
            context.dataStore.edit { prefs ->
                if (agent != null) {
                    prefs[stringPreferencesKey("sel_agent_$sessionId")] = agent
                } else {
                    prefs.remove(stringPreferencesKey("sel_agent_$sessionId"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save selected agent", e)
        }
    }

    suspend fun getSelectedAgent(sessionId: String): String? {
        return try {
            context.dataStore.data.first()[stringPreferencesKey("sel_agent_$sessionId")]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read selected agent", e)
            null
        }
    }

    suspend fun saveSelectedModel(sessionId: String, model: StoredModelSelection?) {
        try {
            context.dataStore.edit { prefs ->
                if (model != null) {
                    prefs[stringPreferencesKey("sel_model_$sessionId")] = model.serialize()
                } else {
                    prefs.remove(stringPreferencesKey("sel_model_$sessionId"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save selected model", e)
        }
    }

    suspend fun getSelectedModel(sessionId: String): StoredModelSelection? {
        return try {
            val serialized = context.dataStore.data.first()[stringPreferencesKey("sel_model_$sessionId")]
            serialized?.let { StoredModelSelection.deserialize(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read selected model", e)
            null
        }
    }

    suspend fun saveSelectedVariant(sessionId: String, variant: String?) {
        try {
            context.dataStore.edit { prefs ->
                if (variant != null) {
                    prefs[stringPreferencesKey("sel_variant_$sessionId")] = variant
                } else {
                    prefs.remove(stringPreferencesKey("sel_variant_$sessionId"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save selected variant", e)
        }
    }

    suspend fun getSelectedVariant(sessionId: String): String? {
        return try {
            context.dataStore.data.first()[stringPreferencesKey("sel_variant_$sessionId")]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read selected variant", e)
            null
        }
    }

    /** 全局上次选择：新会话无专属偏好时继承（否则新会话永远 auto）。 */
    suspend fun saveLastAgent(agent: String?) {
        try {
            context.dataStore.edit { prefs ->
                if (agent != null) prefs[stringPreferencesKey("sel_agent_last")] = agent
                else prefs.remove(stringPreferencesKey("sel_agent_last"))
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save last agent", e) }
    }

    suspend fun getLastAgent(): String? {
        return try { context.dataStore.data.first()[stringPreferencesKey("sel_agent_last")] }
        catch (e: Exception) { Log.e(TAG, "Failed to read last agent", e); null }
    }

    suspend fun saveLastModel(model: StoredModelSelection?) {
        try {
            context.dataStore.edit { prefs ->
                if (model != null) prefs[stringPreferencesKey("sel_model_last")] = model.serialize()
                else prefs.remove(stringPreferencesKey("sel_model_last"))
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save last model", e) }
    }

    suspend fun getLastModel(): StoredModelSelection? {
        return try {
            context.dataStore.data.first()[stringPreferencesKey("sel_model_last")]
                ?.let { StoredModelSelection.deserialize(it) }
        } catch (e: Exception) { Log.e(TAG, "Failed to read last model", e); null }
    }

    suspend fun saveLastVariant(variant: String?) {
        try {
            context.dataStore.edit { prefs ->
                if (variant != null) prefs[stringPreferencesKey("sel_variant_last")] = variant
                else prefs.remove(stringPreferencesKey("sel_variant_last"))
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save last variant", e) }
    }

    suspend fun getLastVariant(): String? {
        return try { context.dataStore.data.first()[stringPreferencesKey("sel_variant_last")] }
        catch (e: Exception) { Log.e(TAG, "Failed to read last variant", e); null }
    }

    /** #14 输入历史（全局环形，\u001F 分隔，最多 50 条）。 */
    suspend fun saveInputHistory(history: List<String>) {
        try {
            context.dataStore.edit { prefs ->
                prefs[stringPreferencesKey("input_history")] = history.take(50).joinToString("\u001F")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save input history", e)
        }
    }

    suspend fun getInputHistory(): List<String> {
        return try {
            context.dataStore.data.first()[stringPreferencesKey("input_history")]
                ?.split("\u001F")?.filter { it.isNotEmpty() }?.take(50) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read input history", e)
            emptyList()
        }
    }

    /** P2 离线队列：断网时存草稿（带当时选定的 agent/model），重连自动发出，上限 20 条。 */
    suspend fun saveOutbox(items: List<OfflineQueuedMessage>) {
        try {
            val encoded = json.encodeToString(ListSerializer(OfflineQueuedMessage.serializer()), items.take(20))
            context.dataStore.edit { prefs -> prefs[stringPreferencesKey("outbox")] = encoded }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save outbox", e)
        }
    }

    suspend fun getOutbox(): List<OfflineQueuedMessage> {
        return try {
            val raw = context.dataStore.data.first()[stringPreferencesKey("outbox")] ?: return emptyList()
            json.decodeFromString(ListSerializer(OfflineQueuedMessage.serializer()), raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read outbox", e)
            emptyList()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
}

/** P2 离线草稿条目（与发送时选定一致，重连后原样发出）。 */
@Serializable
data class OfflineQueuedMessage(
    val sessionId: String,
    val text: String,
    val agent: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val ts: Long = 0L,
)

/** 快捷模板条目：标题（chip 显示）与内容（实际发送）可不同。 */
@Serializable
data class TemplateEntry(
    val title: String = "",
    val content: String = "",
)

/** 置顶条目：可多个；direct=true 的卡单独直达（直达用第一个勾的）。 */
@Serializable
data class PinnedEntry(
    val id: String = "",
    val dir: String? = null,
    val direct: Boolean = false,
)
