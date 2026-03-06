package com.github.oogasawa.llmime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalizeTest {

    private final ConversionService service = new ConversionService();

    @Test
    void removeSpacesBetweenKanji() {
        assertEquals("今日はいい天気ですね", service.normalize("今日 はいい 天気 ですね"));
    }

    @Test
    void removeSpacesBetweenHiragana() {
        assertEquals("こんにちは", service.normalize("こん にち は"));
    }

    @Test
    void removeSpacesBetweenMixed() {
        assertEquals("漢字変換テスト", service.normalize("漢字 変換 テスト"));
    }

    @Test
    void preserveSpaceAroundAscii() {
        // Space between ASCII and Japanese should be preserved
        assertEquals("Hello 世界", service.normalize("Hello 世界"));
    }

    @Test
    void firstLineOnly() {
        assertEquals("漢字変換", service.normalize("漢字変換\nextra line\nanother"));
    }

    @Test
    void stripWhitespace() {
        assertEquals("漢字変換", service.normalize("  漢字変換  "));
    }

    @Test
    void nullAndEmpty() {
        assertNull(service.normalize(null));
        assertEquals("", service.normalize(""));
    }

    @Test
    void multipleConsecutiveSpaces() {
        assertEquals("今日は天気", service.normalize("今日  は  天気"));
    }
}
