package com.reconflow.paymentreconflow.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** M8.4: CSV (formula) injection is neutralised in every text cell; numbers we format stay numbers. */
class CsvCellTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "=1+1",
                "+1",
                "-1+2",
                "@SUM(A1)",
                "=HYPERLINK(\"http://evil.example\",\"click\")",
                "\t=1",
                "\r=1",
                "   =1", // after leading spaces
                "＝1+1", // full-width =
            })
    void aCellThatStartsLikeAFormulaGetsAQuote(String value) {
        assertThat(CsvCell.text(value)).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAY-103", "pay 1=2", "a,b", "line1\nline2", "'already quoted", "100.00", " PAY-1 "})
    void ordinaryTextIsUnchanged(String value) {
        assertThat(CsvCell.text(value)).isEqualTo(value);
    }

    @Test
    void nullAndEmptyAreEmptyCells() {
        assertThat(CsvCell.text(null)).isEmpty();
        assertThat(CsvCell.text("")).isEmpty();
        assertThat(CsvCell.money(null)).isEmpty();
    }

    @Test
    void amountsWeFormatAreNotEscaped() {
        // A negative difference is a number to a spreadsheet, not a formula: quoting it would turn the
        // column into text. Plain notation, never 1E+3.
        assertThat(CsvCell.money(new BigDecimal("-50.0000"))).isEqualTo("-50.0000");
        assertThat(CsvCell.money(new BigDecimal("1E+3"))).isEqualTo("1000");
    }
}
