package com.reconflow.paymentreconflow.shared.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exports a query's rows to object storage as a gzipped CSV and proves the file is complete (M9.16
 * archival). Streamed: rows go from a server-side cursor through gzip to a temp file, never all into
 * memory. Then the file is uploaded, <em>downloaded again</em>, and its rows counted, its amount
 * column summed and its content hashed: the figures returned are what the archive actually holds,
 * so the caller can compare them with the same figures computed in the database before deleting
 * anything. Trusting the upload's success alone would trust every layer between here and the disk.
 *
 * <p>Gzipped CSV is the plan's "easy first version"; Parquet (columnar, queryable in place with
 * DuckDB or Athena) is the target once a Parquet writer is worth its dependency weight.
 */
@Component
public class CsvArchive {

    /** What the archived file contains, measured from the file read back. */
    public record ArchivedFile(
            String key, long rows, @Nullable BigDecimal amountSum, String sha256) {}

    private final JdbcTemplate jdbc;
    private final FileStorage storage;

    CsvArchive(JdbcTemplate jdbc, FileStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /**
     * Runs {@code sql} (in the caller's transaction, so a cursor can stream and row-level security
     * applies), writes the rows to {@code key}, and returns the figures of the stored copy.
     *
     * @param amountColumn a column to sum while reading back, or null
     */
    public ArchivedFile export(String key, String sql, Object[] args, @Nullable String amountColumn) {
        Path file = null;
        Path back = null;
        try {
            file = Files.createTempFile("archive-", ".csv.gz");
            write(file, sql, args);
            try (InputStream in = Files.newInputStream(file)) {
                storage.put(key, in, Files.size(file), "application/gzip");
            }
            back = Files.createTempFile("archive-check-", ".csv.gz");
            storage.download(key, back);
            return measure(key, back, amountColumn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(file);
            deleteQuietly(back);
        }
    }

    private void write(Path file, String sql, Object[] args) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                CSVPrinter csv = new CSVPrinter(
                        writer,
                        CSVFormat.RFC4180.builder().setRecordSeparator('\n').get())) {
            boolean[] header = {false};
            jdbc.query(
                    c -> {
                        PreparedStatement ps = c.prepareStatement(sql);
                        ps.setFetchSize(1_000); // a cursor: pgjdbc streams inside a transaction
                        for (int i = 0; i < args.length; i++) {
                            ps.setObject(i + 1, args[i]);
                        }
                        return ps;
                    },
                    rs -> {
                        try {
                            ResultSetMetaData meta = rs.getMetaData();
                            if (!header[0]) {
                                for (int i = 1; i <= meta.getColumnCount(); i++) {
                                    csv.print(meta.getColumnLabel(i));
                                }
                                csv.println();
                                header[0] = true;
                            }
                            for (int i = 1; i <= meta.getColumnCount(); i++) {
                                csv.print(rs.getString(i)); // text form: NUMERIC keeps its exact digits
                            }
                            csv.println();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private static ArchivedFile measure(String key, Path file, @Nullable String amountColumn) throws IOException {
        MessageDigest sha = sha256();
        long rows = 0;
        BigDecimal sum = amountColumn == null ? null : BigDecimal.ZERO;
        try (InputStream raw = new DigestInputStream(new GZIPInputStream(Files.newInputStream(file)), sha);
                Reader reader = new java.io.InputStreamReader(raw, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.RFC4180
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .get()
                        .parse(reader)) {
            for (CSVRecord record : parser) {
                rows++;
                if (sum != null) {
                    sum = sum.add(new BigDecimal(record.get(amountColumn)));
                }
            }
            raw.transferTo(OutputStream.nullOutputStream()); // hash every byte, trailing ones too
        }
        return new ArchivedFile(key, rows, sum, HexFormat.of().formatHex(sha.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(@Nullable Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a temp file; the OS cleans it up
            }
        }
    }
}
