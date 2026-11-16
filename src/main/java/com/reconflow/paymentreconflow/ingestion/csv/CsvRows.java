package com.reconflow.paymentreconflow.ingestion.csv;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jspecify.annotations.Nullable;

/**
 * A file's data rows, streamed one record at a time: memory holds one record, never the file
 * (phase-M5 §2.1). Close it (try-with-resources): a leaked handle per import accumulates on a
 * long-running worker until the OS limit.
 *
 * <p>Iteration ends early, after one {@link ParsedRow#isMalformed() malformed} row, if the CSV
 * syntax breaks (an unterminated quote swallows the rest of the file, so nothing after it can be
 * read reliably; requirements.md §7.5). Invalid UTF-8 throws {@link CsvEncodingException}.
 */
public final class CsvRows implements Iterable<ParsedRow>, AutoCloseable {

    private static final int MAX_RAW_LINE = 1000;

    private final CSVParser parser;
    private final Iterator<CSVRecord> records;
    private final List<String> header;
    private final Map<Field, Integer> index;
    private final @Nullable Path source;
    private int lastNumber;
    private long linesConsumed;
    private boolean stopped;
    private boolean iterated;

    private CsvRows(
            CSVParser parser,
            Iterator<CSVRecord> records,
            List<String> header,
            Map<Field, Integer> index,
            @Nullable Path source) {
        this.parser = parser;
        this.records = records;
        this.header = header;
        this.index = index;
        this.source = source;
    }

    /**
     * Opens a file: strips a UTF-8 byte-order mark, decodes strictly, reads and checks the header.
     *
     * @throws CsvHeaderException no header, missing or repeated columns
     * @throws CsvEncodingException invalid UTF-8 in the header (row 0)
     */
    public static CsvRows open(Path file, SourceType type) throws IOException {
        return open(Files.newInputStream(file), type, file);
    }

    /** As {@link #open(Path, SourceType)}, for an upload being inspected before it is stored. */
    public static CsvRows open(InputStream in, SourceType type) throws IOException {
        return open(in, type, null);
    }

    private static CsvRows open(InputStream in, SourceType type, @Nullable Path source) throws IOException {
        CSVParser parser = CSVParser.builder()
                .setReader(reader(in))
                // RFC 4180: quotes, embedded commas and line breaks. No header handling by the
                // library: the first record is read as the header here, so data rows are numbered
                // from 1 and the raw header text stays intact for raw_values.
                .setFormat(CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get())
                .get();
        try {
            Iterator<CSVRecord> records = parser.iterator();
            CSVRecord headerRecord;
            try {
                if (!records.hasNext()) {
                    throw new CsvHeaderException(
                            CsvHeaderException.Kind.NO_HEADER, List.of(), "The file has no header row.");
                }
                headerRecord = records.next();
            } catch (UncheckedIOException e) {
                if (isEncoding(e)) {
                    throw new CsvEncodingException(0, e);
                }
                throw new CsvHeaderException(
                        CsvHeaderException.Kind.NO_HEADER, List.of(), "The header row is not valid CSV.");
            }
            List<String> header = headerRecord.toList();
            Map<Field, Integer> index = ColumnMapping.resolve(type, header);
            CsvRows rows = new CsvRows(parser, records, header, index, source);
            rows.linesConsumed = parser.getCurrentLineNumber();
            return rows;
        } catch (RuntimeException e) {
            parser.close();
            throw e;
        }
    }

    private static java.io.Reader reader(InputStream in) throws IOException {
        InputStream withoutBom = org.apache.commons.io.input.BOMInputStream.builder()
                .setInputStream(in)
                .get(); // Excel writes a UTF-8 BOM; it must not become part of the first header name
        // REPORT, not the default REPLACE: never import U+FFFD silently.
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new InputStreamReader(new LineBoundedInputStream(withoutBom), decoder);
    }

    public List<String> header() {
        return header;
    }

    @Override
    public Iterator<ParsedRow> iterator() {
        if (iterated) {
            throw new IllegalStateException("A CSV stream can be read once");
        }
        iterated = true;
        return new Iterator<>() {
            private @Nullable ParsedRow next;

            @Override
            public boolean hasNext() {
                if (next == null && !stopped) {
                    next = advance();
                }
                return next != null;
            }

            @Override
            public ParsedRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ParsedRow row = next;
                next = null;
                return row;
            }
        };
    }

    private @Nullable ParsedRow advance() {
        try {
            if (!records.hasNext()) {
                return null;
            }
            CSVRecord record = records.next();
            lastNumber = (int) record.getRecordNumber() - 1; // record 1 is the header
            linesConsumed = parser.getCurrentLineNumber();
            return toRow(record);
        } catch (UncheckedIOException e) {
            if (isEncoding(e)) {
                throw new CsvEncodingException(lastNumber + 1, e);
            }
            // Broken quoting: report it once, on the record where it starts, and stop reading.
            stopped = true;
            lastNumber++;
            return new ParsedRow(lastNumber, Map.of(), Map.of(), physicalLine(), "Quoted field is never closed");
        }
    }

    private ParsedRow toRow(CSVRecord record) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(record.size(), header.size()); i++) {
            raw.put(rawKey(i), record.get(i));
        }
        String rawRow = CSVFormat.RFC4180.format(record.values());
        if (record.size() != header.size()) {
            return new ParsedRow(
                    lastNumber,
                    Map.of(),
                    raw,
                    rawRow,
                    "The row has %d fields; the header has %d".formatted(record.size(), header.size()));
        }
        Map<Field, String> fields = new EnumMap<>(Field.class);
        index.forEach((field, i) -> fields.put(field, record.get(i)));
        return new ParsedRow(lastNumber, fields, raw, rawRow, null);
    }

    /** raw_values keys are the original header text (N11); a blank header gets a positional name. */
    private String rawKey(int i) {
        String name = header.get(i);
        return name.isBlank() ? "column_" + (i + 1) : name;
    }

    /**
     * The physical line where the unreadable record starts, for its row error. Re-read from the file
     * (only possible when parsing a file, which the worker always does), leniently decoded because
     * it is for display only.
     */
    private @Nullable String physicalLine() {
        if (source == null) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                org.apache.commons.io.input.BOMInputStream.builder()
                        .setInputStream(Files.newInputStream(source))
                        .get(),
                StandardCharsets.UTF_8))) {
            for (long i = 0; i < linesConsumed; i++) {
                if (r.readLine() == null) {
                    return null;
                }
            }
            String line;
            while ((line = r.readLine()) != null && line.isEmpty()) {
                // empty lines are skipped, exactly as the parser skips them
            }
            return line == null || line.length() <= MAX_RAW_LINE ? line : line.substring(0, MAX_RAW_LINE);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isEncoding(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CharacterCodingException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        parser.close();
    }
}
