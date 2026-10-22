package com.taptranslator.app.ui.main

import com.taptranslator.app.data.model.LanguageOption
import com.taptranslator.app.data.model.ScriptType
import com.taptranslator.app.data.model.TranslationTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TranslationModelTest {

    @Test
    fun supportedLanguages_containsExpectedOptions() {
        val sourceLangs = LanguageOption.SUPPORTED_SOURCE
        assertNotNull(sourceLangs.find { it.code == "ja" && it.scriptType == ScriptType.JAPANESE })
        assertNotNull(sourceLangs.find { it.code == "en" && it.scriptType == ScriptType.LATIN })
        assertNotNull(sourceLangs.find { it.code == "zh" && it.scriptType == ScriptType.CHINESE })
        assertNotNull(sourceLangs.find { it.code == "ko" && it.scriptType == ScriptType.KOREAN })
    }

    @Test
    fun translationTone_hasNonEmptyInstructions() {
        for (tone in TranslationTone.values()) {
            assertNotNull(tone.title)
            assertNotNull(tone.instruction)
            assert(tone.instruction.isNotBlank())
        }
    }
}
