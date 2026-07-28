package com.aegis.merged.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantNamesTest {

    @Test
    @DisplayName("Hyphenated tenant ids become title-cased display names")
    void hyphenatedIds() {
        assertThat(TenantNames.displayName("achu-bank")).isEqualTo("Achu Bank");
        assertThat(TenantNames.displayName("globex-bank")).isEqualTo("Globex Bank");
    }

    @Test
    @DisplayName("Blank/null tenant ids fall back to a safe generic name")
    void blankOrNull() {
        assertThat(TenantNames.displayName(null)).isEqualTo("your bank");
        assertThat(TenantNames.displayName("")).isEqualTo("your bank");
    }
}
