package com.reconflow.paymentreconflow.shared.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/** Uses the JsonMapper Boot builds, so it proves the serializer is actually registered. */
@JsonTest
class MoneySerializerTest {

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void writesBigDecimalAsPlainNotationString() {
        assertThat(jsonMapper.writeValueAsString(Map.of("amount", new BigDecimal("1E+3"))))
                .isEqualTo("{\"amount\":\"1000\"}");
    }

    @Test
    void keepsTheStoredScale() {
        assertThat(jsonMapper.writeValueAsString(Map.of("amount", new BigDecimal("1000.0000"))))
                .isEqualTo("{\"amount\":\"1000.0000\"}");
    }

    @Test
    void readsAmountsSentAsStrings() {
        record Body(BigDecimal amount) {}

        Body body = jsonMapper.readValue("{\"amount\":\"12.3400\"}", Body.class);

        assertThat(body.amount()).isEqualByComparingTo("12.34");
        assertThat(body.amount().scale()).isEqualTo(4);
    }
}
