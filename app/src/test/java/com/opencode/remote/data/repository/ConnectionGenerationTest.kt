package com.opencode.remote.data.repository

import android.content.Context
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.dto.AgentInfo
import com.opencode.remote.data.api.dto.ModelInfo
import com.opencode.remote.data.api.dto.ProviderInfo
import com.opencode.remote.data.api.dto.ProviderList
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.network.NetworkMonitor
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.service.SseForegroundService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionGenerationTest {

    private lateinit var apiClient: OConnectorApiClient
    private lateinit var sseClient: OConnectorSseClient
    private lateinit var context: Context
    private lateinit var json: Json
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var connectionPreferences: ConnectionPreferences
    private lateinit var eventBus: SseEventBus
    private lateinit var repository: OConnectorRepositoryImpl

    @Before
    fun setUp() {
        apiClient = mockk(relaxed = true)
        sseClient = mockk(relaxed = true)
        context = mockk(relaxed = true)
        json = Json { ignoreUnknownKeys = true }
        networkMonitor = mockk(relaxed = true)
        connectionPreferences = mockk(relaxed = true)
        eventBus = SseEventBus()
        repository = OConnectorRepositoryImpl(apiClient, sseClient, context, json, networkMonitor, connectionPreferences, eventBus)

        // Mock SseForegroundService companion object to prevent Android API calls
        mockkObject(SseForegroundService)
        every { SseForegroundService.start(any(), any()) } returns Unit
        every { SseForegroundService.stop(any()) } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `initial generation is 0`() {
        assertEquals(0L, repository.currentGeneration)
    }

    @Test
    fun `connect increments generation`() {
        val config = ConnectionConfig("192.168.1.1", 4096, "user", "pass")
        repository.connect(config)
        assertEquals(1L, repository.currentGeneration)
    }

    @Test
    fun `connect increments generation on each call`() {
        val config = ConnectionConfig("192.168.1.1", 4096, "user", "pass")
        repository.connect(config)
        assertEquals(1L, repository.currentGeneration)

        repository.disconnect()
        // disconnect does NOT change generation
        assertEquals(1L, repository.currentGeneration)

        repository.connect(config)
        assertEquals(2L, repository.currentGeneration)
    }

    @Test
    fun `disconnect does NOT change generation`() {
        val config = ConnectionConfig("192.168.1.1", 4096, "user", "pass")
        repository.connect(config)
        val genBefore = repository.currentGeneration

        repository.disconnect()

        assertEquals(genBefore, repository.currentGeneration)
    }

    @Test
    fun `switchToServer increments generation`() {
        val config = ConnectionConfig("192.168.1.1", 4096, "user", "pass")
        repository.connect(config)
        assertEquals(1L, repository.currentGeneration)

        val newConfig = ConnectionConfig("192.168.1.2", 4096, "user", "pass")
        repository.switchToServer("server2", newConfig)
        // disconnect (no gen change) + connect (increment to 2)
        assertEquals(2L, repository.currentGeneration)
    }

    @Test
    fun `generation only increments never resets`() {
        val config = ConnectionConfig("192.168.1.1", 4096, "user", "pass")

        // Connect 3 times
        repository.connect(config)       // gen=1
        repository.disconnect()           // gen stays 1
        repository.connect(config)       // gen=2
        repository.disconnect()           // gen stays 2
        repository.connect(config)       // gen=3

        assertEquals(3L, repository.currentGeneration)

        // Disconnect and verify it stays at 3
        repository.disconnect()
        assertEquals(3L, repository.currentGeneration)
    }
}
