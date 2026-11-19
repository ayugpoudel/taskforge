package dev.taskforge;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.util.*;

/** One pass, O(columns * maximum field size) memory, no row contents in diagnostics. */
public final class CsvAnalytics {
    public record Numeric(long count, BigDecimal min, BigDecimal max, BigDecimal sum, BigDecimal mean) {}
    public record Column(String name, String inferredType, long missing, Numeric numeric) {}
    public record Report(long rowCount, long validRows, long invalidRows, List<Long> invalidRowNumbers,
                         List<Column> columns) {}
    public static final class InvalidCsv extends RuntimeException {
        public InvalidCsv(String message) { super(message); }
    }
    private static final MathContext MC = MathContext.DECIMAL128;

    public Report analyze(InputStream input, Runnable checkpoint) {
        var settings = new CsvParserSettings();
        settings.setMaxColumns(256);
        settings.setMaxCharsPerColumn(65_536);
        settings.setReadInputOnSeparateThread(false);
        settings.setLineSeparatorDetectionEnabled(true);
        settings.setSkipEmptyLines(false);
        // Reject broken quoting instead of silently accepting ambiguous records.
        settings.setUnescapedQuoteHandling(com.univocity.parsers.csv.UnescapedQuoteHandling.RAISE_ERROR);
        var parser = new CsvParser(settings);
        long rows = 0, valid = 0, invalid = 0;
        var errors = new ArrayList<Long>();
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader = new PushbackReader(new InputStreamReader(input, decoder), 1);
            int first = reader.read();
            if (first != -1 && first != 0xFEFF) reader.unread(first);
            parser.beginParsing(new CsvSyntaxReader(reader));
            String[] headers = parser.parseNext();
            if (headers == null || headers.length == 0) throw new InvalidCsv("CSV requires a header.");
            var names = new HashSet<String>();
            for (String header : headers) {
                if (header == null || header.isBlank() || !names.add(header.trim()) || header.indexOf(0) >= 0)
                    throw new InvalidCsv("Column names must be nonblank and unique.");
            }
            var columns = Arrays.stream(headers).map(h -> new Accumulator(h.trim())).toList();
            String[] row;
            while ((row = parser.parseNext()) != null) {
                rows++;
                if ((rows & 255) == 0) checkpoint.run();
                if (row.length != headers.length) {
                    invalid++;
                    if (errors.size() < 20) errors.add(rows + 1); // logical CSV record, including header
                    continue;
                }
                valid++;
                for (int c = 0; c < row.length; c++) columns.get(c).accept(row[c]);
            }
            checkpoint.run();
            return new Report(rows, valid, invalid, List.copyOf(errors), columns.stream().map(Accumulator::finish).toList());
        } catch (InvalidCsv e) {
            throw e;
        } catch (com.univocity.parsers.common.TextParsingException | IOException e) {
            // Parser errors can contain source data; never expose their message or cause.
            throw new InvalidCsv("CSV cannot be parsed within UTF-8, column, and field limits.");
        } finally { parser.stopParsing(); }
    }

    private static final class Accumulator {
        final String name;
        long missing, present, numbers, booleans;
        BigDecimal min, max, sum = BigDecimal.ZERO;
        Accumulator(String name) { this.name = name; }
        void accept(String value) {
            if (value == null || value.isBlank()) { missing++; return; }
            String v = value.trim();
            present++;
            if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) booleans++;
            BigDecimal number = number(v);
            if (number != null) {
                numbers++;
                min = min == null || number.compareTo(min) < 0 ? number : min;
                max = max == null || number.compareTo(max) > 0 ? number : max;
                sum = sum.add(number, MC);
            }
        }
        Column finish() {
            String type = present == 0 ? "EMPTY" : numbers == present ? "NUMBER" : booleans == present ? "BOOLEAN" : "STRING";
            return new Column(name, type, missing, new Numeric(numbers, min, max, numbers == 0 ? null : sum,
                    numbers == 0 ? null : sum.divide(BigDecimal.valueOf(numbers), MC)));
        }
        static BigDecimal number(String v) {
            if (v.length() > 128 || !v.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?")) return null;
            try {
                var n = new BigDecimal(v);
                return Math.abs(n.scale()) > 308 || n.precision() > 128 ? null : n;
            } catch (NumberFormatException e) { return null; }
        }
    }
}
