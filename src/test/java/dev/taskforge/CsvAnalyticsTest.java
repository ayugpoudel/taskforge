package dev.taskforge;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class CsvAnalyticsTest {
    private CsvAnalytics.Report report(String s) {
        return new CsvAnalytics().analyze(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), () -> {});
    }
    @Test void independentHandCalculatedAggregatesAndMissingValues() {
        var r = report("name,amount,active\nAlice,2.5,true\nBob,-1.5,false\n,4,true\nChris,,\n");
        assertThat(r.rowCount()).isEqualTo(4);
        assertThat(r.validRows()).isEqualTo(4);
        assertThat(r.columns().get(0).missing()).isEqualTo(1);
        var c = r.columns().get(1);
        assertThat(c.inferredType()).isEqualTo("NUMBER");
        assertThat(c.numeric().min()).isEqualByComparingTo("-1.5");
        assertThat(c.numeric().max()).isEqualByComparingTo("4");
        assertThat(c.numeric().sum()).isEqualByComparingTo("5");
        assertThat(c.numeric().mean()).isEqualByComparingTo(new BigDecimal("5").divide(new BigDecimal("3"), java.math.MathContext.DECIMAL128));
        assertThat(r.columns().get(2).inferredType()).isEqualTo("BOOLEAN");
    }
    @Test void reportsWrongWidthWithoutLeakingContents() {
        var r = report("a,b\n1,2\nsecret\n3,4,5\n");
        assertThat(r.invalidRows()).isEqualTo(2);
        assertThat(r.invalidRowNumbers()).containsExactly(3L,4L);
        assertThat(r.columns().get(0).numeric().sum()).isEqualByComparingTo("1");
    }
    @Test void quotedNewlineAndBomAreSupported() {
        var r = report("\uFEFFname,n\r\n\"a,b\nnext\",3\r\n");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.columns().get(0).name()).isEqualTo("name");
    }
    @Test void validatesHeadersAndFieldLimits() {
        assertThatThrownBy(() -> report("")).isInstanceOf(CsvAnalytics.InvalidCsv.class);
        assertThatThrownBy(() -> report("a,a\n1,2")).isInstanceOf(CsvAnalytics.InvalidCsv.class);
        assertThatThrownBy(() -> report("a\n" + "x".repeat(65_537))).isInstanceOf(CsvAnalytics.InvalidCsv.class);
    }
    @Test void emptyAndMixedTypeSemanticsAreExplicit() {
        var r=report("a,b,c\n,NaN,1\n,2,text\n");
        assertThat(r.columns().get(0).inferredType()).isEqualTo("EMPTY");
        assertThat(r.columns().get(1).inferredType()).isEqualTo("STRING");
        assertThat(r.columns().get(1).numeric().count()).isEqualTo(1);
        assertThat(r.columns().get(2).numeric().sum()).isEqualByComparingTo("1");
    }
    @Test void checkpointCanAbortAndHugeExponentsStayBounded() {
        assertThat(report("a\n1e999\n").columns().get(0).inferredType()).isEqualTo("STRING");
        assertThatThrownBy(() -> new CsvAnalytics().analyze(new ByteArrayInputStream("a\n1".getBytes()),
                () -> { throw new IllegalStateException("cancel"); })).hasMessage("cancel");
    }
    @Test void invalidUtf8IsRejectedWithoutRawContents() {
        assertThatThrownBy(() -> new CsvAnalytics().analyze(new ByteArrayInputStream(new byte[]{'a','\n',(byte)0xc3,0x28}), () -> {}))
                .isInstanceOf(CsvAnalytics.InvalidCsv.class).hasMessageNotContaining("0xc3");
    }
    @Test void structuralErrorSamplesAreBounded() {
        var r=report("a,b\n"+"bad\n".repeat(100));
        assertThat(r.invalidRows()).isEqualTo(100);
        assertThat(r.invalidRowNumbers()).hasSize(20);
    }
    @Test void malformedQuotingAndBinaryInputAreRejected() {
        for(String csv:new String[]{"a\n\"unterminated", "a\nab\"cd", "a\n\"closed\"suffix", "a\nx\u0000y"})
            assertThatThrownBy(()->report(csv)).isInstanceOf(CsvAnalytics.InvalidCsv.class);
        assertThat(report("a\n\"quoted \"\"word\"\"\"\n").rowCount()).isEqualTo(1);
    }
    @Test void objectByteLimitIsEnforcedWithoutAllocatingAnEntireFile() {
        var input=new LimitedInputStream(new ByteArrayInputStream("a\n123456789".getBytes()),5);
        assertThatThrownBy(()->new CsvAnalytics().analyze(input,()->{})).isInstanceOf(CsvAnalytics.InvalidCsv.class);
    }
}
