package com.example.aravatarguide

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Helper class that uses Groq's FREE API (Llama 3.3 70B) for conversational AI.
 * Maintains conversation history so the avatar feels like a real friend.
 */
class GroqChatHelper(private val apiKey: String) {

    companion object {
        private const val TAG = "GroqChatHelper"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.3-70b-versatile"
        private const val MAX_HISTORY = 20 // Keep last 20 messages for context
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Conversation history for context
    private val conversationHistory = mutableListOf<Pair<String, String>>() // role, content

    private var systemPrompt = ""

    /**
     * Set the system prompt with available locations context.
     */
    private var currentLocationContext: String = ""
    
    fun setSystemPrompt(availableLocations: List<String>) {
        systemPrompt = """
            You are a friendly, warm, and helpful AR Avatar Guide inside a building.
            Your name is Mitsway. You talk like a close friend — casual, supportive, and fun.
            
            You help people navigate inside a building using augmented reality, but you're also a knowledgeable companion who can chat about anything.
            
            Available locations in this building: ${availableLocations.joinToString(", ")}.
            ${if (currentLocationContext.isNotEmpty()) "Current user location: $currentLocationContext" else ""}
            
            PERSONALITY:
            - You're enthusiastic, helpful, and love to chat
            - You can answer questions about the building, locations, people, history, or general topics
            - You're knowledgeable and can provide interesting facts or information
            - You remember the conversation context and can reference previous topics
            - You're supportive and encouraging
            
            RULES:
            1. If the user wants to go to a specific location, reply with EXACTLY this format on a new line: NAVIGATE_TO: Location Name
               Use the exact location name from the available locations list. Do NOT wrap it in brackets or quotes.
            2. If the user just says a location name (like "library" or "room 101"), treat it as a navigation request and respond with NAVIGATE_TO: Location Name
            3. For general conversation, questions, or chitchat, respond naturally and helpfully like a knowledgeable friend.
            4. If asked about a location, person, or topic (like "How is Hinton?" or "Tell me about the lab"), provide interesting, relevant information.
            5. Keep responses SHORT (1-2 sentences max) since they will be spoken aloud via text-to-speech.
            6. Don't use emojis or special characters since the response is spoken.
            7. If someone says hello or greets you, greet them back warmly and ask how you can help.
            8. If you're unsure which location they mean, ask them to clarify from the available list.
            9. You can make educated guesses or provide general knowledge if you don't have specific information.
            10. Be conversational - users can ask you anything, not just navigation questions.
        """.trimIndent()
    }
    
    /**
     * Update the current location context so the AI knows where the user is.
     * This helps provide location-aware responses.
     */
    fun updateCurrentLocation(locationName: String) {
        currentLocationContext = locationName
        // Refresh system prompt with new location
        val locations = systemPrompt
            .substringAfter("Available locations in this building: ")
            .substringBefore(".")
            .split(", ")
        setSystemPrompt(locations)
    }

    /**
     * Send a message and get a response. This is a BLOCKING call — run on IO thread.
     */
    fun chat(userMessage: String): ChatResult {
        // Add user message to history
        conversationHistory.add("user" to userMessage)

        // Trim history if too long
        while (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeAt(0)
        }

        // Build messages array
        val messagesArray = JSONArray()

        // System message
        if (systemPrompt.isNotBlank()) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Conversation history
        for ((role, content) in conversationHistory) {
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        // Build request body
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 150)
        }

        Log.d(TAG, "Sending to Groq: $userMessage")

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq API error ${response.code}: $responseBody")
                val errorMsg = parseErrorMessage(responseBody, response.code)
                ChatResult.Error(errorMsg)
            } else {
                val jsonResponse = JSONObject(responseBody)
                val assistantMessage = jsonResponse
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                Log.d(TAG, "Groq response: $assistantMessage")

                // Add assistant response to history
                conversationHistory.add("assistant" to assistantMessage)

                // Check if response contains navigation command
                if (assistantMessage.contains("NAVIGATE_TO:")) {
                    val destination = assistantMessage
                        .substringAfter("NAVIGATE_TO:")
                        .trim()
                        .lines()
                        .first()
                        .trim()
                        .removeSurrounding("[", "]")
                        .removeSurrounding("\"", "\"")
                        .removeSurrounding("'", "'")
                        .trim()
                    // Extract any conversational text before the command
                    val conversationalPart = assistantMessage
                        .substringBefore("NAVIGATE_TO:")
                        .trim()
                    ChatResult.Navigation(destination, conversationalPart)
                } else {
                    ChatResult.Message(assistantMessage)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            ChatResult.Error("I'm having trouble connecting to the internet. Please check your connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            ChatResult.Error("Something went wrong. Please try again.")
        }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val message = error?.optString("message") ?: "Unknown error"
            when (code) {
                401 -> "My API key is invalid. Please check the Groq API key."
                429 -> "I'm getting too many requests right now. Please wait a moment and try again."
                503 -> "The AI service is temporarily busy. Please try again in a moment."
                else -> "AI error: $message"
            }
        } catch (e: Exception) {
            "AI error (code $code). Please try again."
        }
    }

    /**
     * Clear conversation history (e.g., on new session).
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * Result types from chat.
     */
    sealed class ChatResult {
        data class Message(val text: String) : ChatResult()
        data class Navigation(val destination: String, val preText: String) : ChatResult()
        data class Error(val message: String) : ChatResult()
    }
}
