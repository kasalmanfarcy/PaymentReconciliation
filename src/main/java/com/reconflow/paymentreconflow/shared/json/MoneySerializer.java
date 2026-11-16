package com.reconflow.paymentreconflow.shared.json;

import java.math.BigDecimal;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Writes every {@link BigDecimal} as a JSON string in plain notation at its own scale:
 * {@code new BigDecimal("1000.0000")} becomes {@code "1000.0000"}, and {@code 1E+3} becomes
 * {@code "1000"}, never {@code 1E+3}.
 *
 * <p>Why a string (api-design.md §1, reconciliation-rules.md MN7): JavaScript clients parse a
 * JSON number into a binary double, which cannot represent most decimal amounts exactly.
 * Why {@code toPlainString()}: {@code toString()} may switch to scientific notation.
 *
 * <p>{@link JacksonComponent} registers it with Boot's JsonMapper (Jackson 3, package
 * {@code tools.jackson}), and {@code @JsonTest} slices pick it up too. Reading still accepts
 * both strings and numbers, so clients may send either.
 */
@JacksonComponent
public class MoneySerializer extends StdSerializer<BigDecimal> {

    public MoneySerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toPlainString());
    }
}
