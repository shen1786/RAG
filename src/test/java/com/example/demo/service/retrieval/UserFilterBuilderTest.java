package com.example.demo.service.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class UserFilterBuilderTest {

    @InjectMocks
    private UserFilterBuilder userFilterBuilder;

    @Test
    void build_withNormalUserId_returnsFilterExpression() {
        String result = userFilterBuilder.build("abc123");

        assertEquals("user_id == 'abc123'", result);
    }

    @Test
    void build_withSpecialChars_escapesRedisTagCharacters() {
        // Dashes and dots must be backslash-escaped
        String result = userFilterBuilder.build("abc-123");

        assertEquals("user_id == 'abc\\-123'", result);
    }

    @Test
    void build_withDotsAndMixedSpecialChars_escapesAll() {
        String result = userFilterBuilder.build("user.name-01");

        assertEquals("user_id == 'user\\.name\\-01'", result);
    }

    @Test
    void build_withUnderscore_doesNotEscape() {
        String result = userFilterBuilder.build("user_123");

        assertEquals("user_id == 'user_123'", result);
    }

    @Test
    void build_withNull_returnsEmptyString() {
        assertEquals("", userFilterBuilder.build(null));
    }

    @Test
    void build_withBlank_returnsEmptyString() {
        assertEquals("", userFilterBuilder.build(""));
        assertEquals("", userFilterBuilder.build("   "));
    }
}
