package com.reconflow.paymentreconflow.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M5.10: the streaming parser, on files written to disk exactly as the worker reads them. */
class CsvRowsTest {

    static final String LEDGER_HEADER = "reference,amount,currency,status,transaction_date\n";

    @TempDir
    Path dir;

    @Test
    void theFirstDataRowIsRowOne() throws IOException {
        List<ParsedRow> rows =
                read(LEDGER_HEADER + "PAY-1,1.00,AED,SUCCESS,2026-10-01\nPAY-2,2.00,AED,SUCCESS,2026-10-01\n");

        assertThat(rows).extracting(ParsedRow::number).containsExactly(1, 2);
        assertThat(rows.getFirst().get(Field.REFERENCE)).isEqualTo("PAY-1");
    }

    @Test
    void stripsAUtf8ByteOrderMark() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Path file = write(
                concat(bom, (LEDGER_HEADER + "PAY-1,1.00,AED,SUCCESS,2026-10-01\n").getBytes(StandardCharsets.UTF_8)));

        try (CsvRows rows = CsvRows.open(file, SourceType.INTERNAL_LEDGER)) {
            assertThat(rows.header().getFirst()).isEqualTo("reference");
            assertThat(rows.iterator().next().get(Field.REFERENCE)).isEqualTo("PAY-1");
        }
    }

    @Test
    void keepsAQuotedCommaInOneField() throws IOException {
        ParsedRow row = read(LEDGER_HEADER + "PAY-402,\"1,000.00\",AED,SUCCESS,2026-10-05\n")
                .getFirst();

        assertThat(row.isMalformed()).isFalse();
        assertThat(row.get(Field.AMOUNT)).isEqualTo("1,000.00");
    }

    @Test
    void aQuotedLineBreakIsOneRecordAndRowNumbersStayRecordNumbers() throws IOException {
        String header = "reference,amount,currency,status,transaction_date,description\n";
        List<ParsedRow> rows = read(header
                + "PAY-1,1.00,AED,SUCCESS,2026-10-01,\"two\nlines\"\n"
                + "PAY-2,2.00,AED,SUCCESS,2026-10-01,one line\n");

        assertThat(rows).extracting(ParsedRow::number).containsExactly(1, 2);
        assertThat(rows.getFirst().rawValues()).containsEntry("description", "two\nlines");
    }

    @Test
    void matchesHeaderNamesIgnoringCaseAndSurroundingSpacesInAnyOrder() throws IOException {
        ParsedRow row =
                read("Transaction_At,AMOUNT, Reference ,currency,Status\r\n2026-10-01T09:15:00+04:00,1000.00,PAY-101,AED,SUCCESS\r\n")
                        .getFirst();

        assertThat(row.get(Field.REFERENCE)).isEqualTo("PAY-101");
        assertThat(row.get(Field.TRANSACTION_AT)).isEqualTo("2026-10-01T09:15:00+04:00");
        assertThat(row.has(Field.TRANSACTION_DATE)).isFalse();
        // raw_values keep the original header text (N11)
        assertThat(row.rawValues()).containsKeys("Transaction_At", " Reference ");
    }

    @Test
    void rejectsARepeatedHeaderName() {
        assertThatThrownBy(() -> read(
                        "reference,amount,currency,status,transaction_date,Amount \nX,1,AED,SUCCESS,2026-10-01,1\n"))
                .isInstanceOfSatisfying(CsvHeaderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(CsvHeaderException.Kind.DUPLICATE_COLUMNS);
                    assertThat(e.columns()).containsExactly("amount");
                });
    }

    @Test
    void listsMissingRequiredColumnsInContractOrder() {
        assertThatThrownBy(() -> CsvRows.open(write("ref,amt,ccy,status,transaction_at\n"), SourceType.GATEWAY))
                .isInstanceOfSatisfying(CsvHeaderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(CsvHeaderException.Kind.MISSING_COLUMNS);
                    assertThat(e.columns()).containsExactly("merchant_reference", "amount", "currency");
                });
        assertThatThrownBy(() -> read("reference,amount,currency,status\n"))
                .isInstanceOfSatisfying(
                        CsvHeaderException.class,
                        e -> assertThat(e.columns()).containsExactly(CsvContract.LEDGER_DATE_EITHER));
    }

    @Test
    void anEmptyFileHasNoHeader() {
        assertThatThrownBy(() -> read("\n\n"))
                .isInstanceOfSatisfying(
                        CsvHeaderException.class,
                        e -> assertThat(e.kind()).isEqualTo(CsvHeaderException.Kind.NO_HEADER));
    }

    @Test
    void skipsEmptyLinesWithoutCountingThem() throws IOException {
        List<ParsedRow> rows =
                read(LEDGER_HEADER + "\nPAY-1,1.00,AED,SUCCESS,2026-10-01\n\n\nPAY-2,2.00,AED,SUCCESS,2026-10-01\n\n");

        assertThat(rows).extracting(ParsedRow::number).containsExactly(1, 2);
    }

    @Test
    void keepsAnUnknownExtraColumnInRawValuesOnly() throws IOException {
        ParsedRow row =
                read("reference,amount,currency,status,transaction_date,order_id\nPAY-1,1.00,AED,SUCCESS,2026-10-01,O-9\n")
                        .getFirst();

        assertThat(row.rawValues()).containsEntry("order_id", "O-9");
        assertThat(row.fields()).doesNotContainValue("O-9");
    }

    @Test
    void aRowWithTheWrongFieldCountIsMalformed() throws IOException {
        ParsedRow row = read(LEDGER_HEADER + "PAY-402,1,000.00,AED,SUCCESS,2026-10-05\n")
                .getFirst();

        assertThat(row.isMalformed()).isTrue();
        assertThat(row.malformed()).contains("6 fields").contains("5");
    }

    @Test
    void anUnterminatedQuoteIsOneMalformedRowAndReadingStops() throws IOException {
        List<ParsedRow> rows = read(LEDGER_HEADER
                + "PAY-401,100.00,AED,SUCCESS,2026-10-05\n"
                + "PAY-409,\"60.00,AED,SUCCESS,2026-10-05\n"
                + "PAY-410,70.00,AED,SUCCESS,2026-10-05\n");

        assertThat(rows).hasSize(2);
        ParsedRow broken = rows.get(1);
        assertThat(broken.number()).isEqualTo(2);
        assertThat(broken.isMalformed()).isTrue();
        assertThat(broken.rawRow()).isEqualTo("PAY-409,\"60.00,AED,SUCCESS,2026-10-05");
    }

    @Test
    void invalidUtf8NamesTheRowItIsOnEvenFarIntoTheFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(LEDGER_HEADER.getBytes(StandardCharsets.UTF_8));
        for (int i = 1; i <= 1000; i++) {
            out.write(("PAY-" + i + ",1.00,AED,SUCCESS,2026-10-01\n").getBytes(StandardCharsets.UTF_8));
            if (i == 899) {
                out.write(new byte[] {'P', 'A', 'Y', (byte) 0xC3, (byte) 0x28}); // invalid 2-byte sequence
                out.write(",1.00,AED,SUCCESS,2026-10-01\n".getBytes(StandardCharsets.UTF_8));
                i++;
            }
        }
        Path file = write(out.toByteArray());

        List<Integer> seen = new ArrayList<>();
        try (CsvRows rows = CsvRows.open(file, SourceType.INTERNAL_LEDGER)) {
            assertThatThrownBy(() -> rows.forEach(r -> seen.add(r.number())))
                    .isInstanceOfSatisfying(
                            CsvEncodingException.class,
                            e -> assertThat(e.rowNumber()).isEqualTo(900));
        }
        assertThat(seen).hasSize(899);
    }

    @Test
    void invalidUtf8InTheHeaderIsReportedAsTheHeader() throws IOException {
        Path file = write(concat(new byte[] {'r', 'e', (byte) 0xE9}, ",amount\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> CsvRows.open(file, SourceType.INTERNAL_LEDGER))
                .isInstanceOfSatisfying(
                        CsvEncodingException.class,
                        e -> assertThat(e.rowNumber()).isZero());
    }

    private List<ParsedRow> read(String content) throws IOException {
        List<ParsedRow> out = new ArrayList<>();
        try (CsvRows rows = CsvRows.open(write(content), SourceType.INTERNAL_LEDGER)) {
            rows.forEach(out::add);
        }
        return out;
    }

    private Path write(String content) throws IOException {
        return write(content.getBytes(StandardCharsets.UTF_8));
    }

    private Path write(byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "rows-", ".csv");
        Files.write(file, content);
        return file;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
