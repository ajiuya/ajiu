package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.R
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatBubbleDataStore by preferencesDataStore(
    name = "chat_bubble_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class ChatBubbleStyle(val id: String, val labelRes: Int) {
    CLASSIC("classic", R.string.chat_bubble_style_classic),
    SHADOW("shadow", R.string.chat_bubble_style_shadow),
    MINIMAL("minimal", R.string.chat_bubble_style_minimal),
    DARK("dark", R.string.chat_bubble_style_dark),
    SQUARE("square", R.string.chat_bubble_style_square),
    GREEN("green", R.string.chat_bubble_style_green),
    VALENTINE("valentine", R.string.chat_bubble_style_valentine),
    ORIGINAL("original", R.string.chat_bubble_style_original),
    DOCUMENT("document", R.string.chat_bubble_style_document);

    companion object {
        val userStyles: List<ChatBubbleStyle> = entries.filter { it != DOCUMENT }
        val assistantStyles: List<ChatBubbleStyle> = listOf(DOCUMENT) + userStyles

        fun fromId(id: String?): ChatBubbleStyle? = entries.firstOrNull { it.id == id }

        fun userStyleFromId(id: String?): ChatBubbleStyle =
            fromId(id)?.takeIf { it != DOCUMENT } ?: ORIGINAL

        fun assistantStyleFromId(id: String?): ChatBubbleStyle = fromId(id) ?: DOCUMENT
    }
}

@Singleton
class ChatBubbleSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val USER_STYLE_KEY = stringPreferencesKey("user_style_id")
        val ASSISTANT_STYLE_KEY = stringPreferencesKey("assistant_style_id")
    }

    val userStyleFlow: Flow<ChatBubbleStyle> = context.chatBubbleDataStore.data.map {
        ChatBubbleStyle.userStyleFromId(it[USER_STYLE_KEY])
    }

    val assistantStyleFlow: Flow<ChatBubbleStyle> = context.chatBubbleDataStore.data.map {
        ChatBubbleStyle.assistantStyleFromId(it[ASSISTANT_STYLE_KEY])
    }

    suspend fun setUserStyle(style: ChatBubbleStyle) {
        context.chatBubbleDataStore.edit {
            it[USER_STYLE_KEY] = ChatBubbleStyle.userStyleFromId(style.id).id
        }
    }

    suspend fun setAssistantStyle(style: ChatBubbleStyle) {
        context.chatBubbleDataStore.edit { it[ASSISTANT_STYLE_KEY] = style.id }
    }

    suspend fun userStyleSnapshot(): ChatBubbleStyle = userStyleFlow.first()

    suspend fun assistantStyleSnapshot(): ChatBubbleStyle = assistantStyleFlow.first()

    suspend fun restore(userStyleId: String?, assistantStyleId: String?) {
        if (userStyleId == null && assistantStyleId == null) return
        context.chatBubbleDataStore.edit {
            if (userStyleId != null) {
                it[USER_STYLE_KEY] = ChatBubbleStyle.userStyleFromId(userStyleId).id
            }
            if (assistantStyleId != null) {
                it[ASSISTANT_STYLE_KEY] = ChatBubbleStyle.assistantStyleFromId(assistantStyleId).id
            }
        }
    }
}
