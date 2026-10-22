package com.taptranslator.app.data.model

import android.graphics.Rect
import kotlinx.serialization.Serializable

/**
 * Recognizer script enum
 */
enum class ScriptType {
    LATIN,
    KOREAN,
    JAPANESE,
    CHINESE
}

data class LanguageOption(
    val code: String,
    val displayName: String,
    val scriptType: ScriptType = ScriptType.LATIN
) {
    companion object {
        val SUPPORTED_SOURCE = listOf(
            LanguageOption("zh", "중국어 (Chinese)", ScriptType.CHINESE),
            LanguageOption("ja", "일본어 (Japanese)", ScriptType.JAPANESE),
            LanguageOption("en", "영어 (English)", ScriptType.LATIN),
            LanguageOption("ko", "한국어 (Korean)", ScriptType.KOREAN),
            LanguageOption("auto", "자동 감지 (모든 언어)", ScriptType.LATIN)
        )

        val SUPPORTED_TARGET = listOf(
            LanguageOption("ko", "한국어 (Korean)", ScriptType.KOREAN),
            LanguageOption("en", "영어 (English)", ScriptType.LATIN),
            LanguageOption("ja", "일본어 (Japanese)", ScriptType.JAPANESE),
            LanguageOption("zh", "중국어 (Chinese)", ScriptType.CHINESE)
        )
    }
}

enum class TranslationTone(val title: String, val instruction: String) {
    GAME(
        "게임/서브컬처 (대화체/고유명사 최적화)",
        "Translate in a natural, immersive dialogue tone suitable for games/manga. Adapt colloquialisms, honorifics, gaming terms, and emotions accurately into standard natural Korean phrasing."
    ),
    WEBTOON(
        "만화/웹툰 (감정/말풍선 대사체)",
        "Translate in a vivid webtoon/comic style. Make sound effects, emotions, and character voices sound natural and lively in Korean."
    ),
    GENERAL(
        "일반 (표준 번역)",
        "Translate accurately and naturally with standard polite polite/informal tone matching the original context."
    )
}

enum class OverlayDisplayMode(val title: String) {
    OVERLAY_BOXES("원문 위치에 번역 덮어쓰기 (오버레이)"),
    POPUP_CARD("하단 팝업 카드로 전체보기"),
    BOTH("오버레이 + 하단 카드 모두 표시")
}

data class RecognizedBlock(
    val id: Int,
    val originalText: String,
    val rect: Rect,
    var translatedText: String = ""
)

@Serializable
data class GeminiTextItem(
    val id: Int,
    val text: String
)

@Serializable
data class GeminiTranslationItem(
    val id: Int,
    val translation: String
)
