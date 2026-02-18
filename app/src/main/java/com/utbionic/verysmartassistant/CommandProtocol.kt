package com.utbionic.verysmartassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured command format for ESP32 communication
 * Commands are sent as JSON for easy parsing on both sides
 */
@Serializable
data class Command(
    @SerialName("cmd") val command: String,
    @SerialName("target") val target: String,  // "apartment" or "suite"
    @SerialName("duration") val durationMs: Int = 5000  // Default 5 seconds
)

/**
 * Response from ESP32
 */
@Serializable
data class CommandResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("message") val message: String,
    @SerialName("target") val target: String? = null,
    @SerialName("error") val error: String? = null
)

object CommandProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Serialize a command to JSON string
     */
    fun toJson(command: Command): String {
        return json.encodeToString(Command.serializer(), command)
    }

    /**
     * Parse a response from JSON string
     */
    fun parseResponse(responseString: String): CommandResponse? {
        return try {
            json.decodeFromString(CommandResponse.serializer(), responseString.trim())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create a door open command
     */
    fun createDoorCommand(target: String, durationMs: Int = 5000): Command {
        return Command(
            command = "PUSH_BUTTON", target = target, durationMs = durationMs
        )
    }
}
