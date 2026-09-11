package com.xupan.server.chat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatContentPolicyTest {

    private final ChatContentPolicy policy = new ChatContentPolicy();

    @Test
    void normalizesUnicodeAndPreservesAllowedWhitespace() {
        assertThat(policy.normalize("  e\u0301\n你好\t ")).isEqualTo("é\n你好");
    }

    @Test
    void rejectsHtmlControlCharactersAndCodePointOverflow() {
        assertThatThrownBy(() -> policy.normalize("<b>危险</b>"))
                .hasMessageContaining("CHAT_MESSAGE_HTML_FORBIDDEN");
        assertThatThrownBy(() -> policy.normalize("hello\u0000"))
                .hasMessageContaining("CHAT_MESSAGE_CONTROL_CHAR");
        assertThatThrownBy(() -> policy.normalize("😀".repeat(501)))
                .hasMessageContaining("CHAT_MESSAGE_TOO_LONG");
    }

    @Test
    void validatesClientIdAndPageLimit() {
        assertThatThrownBy(() -> policy.requireClientMessageId(" "))
                .hasMessageContaining("CHAT_CLIENT_MESSAGE_ID_INVALID");
        assertThatThrownBy(() -> policy.validateLimit(101))
                .hasMessageContaining("CHAT_MESSAGE_LIMIT_INVALID");
    }
}
