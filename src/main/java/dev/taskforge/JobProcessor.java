package dev.taskforge;

import java.io.InputStream;
import org.springframework.stereotype.Component;

public interface JobProcessor {
    String type();
    Object process(InputStream input, Runnable checkpoint);
}
@Component
class CsvJobProcessor implements JobProcessor {
    @Override public String type() { return "CSV_ANALYTICS"; }
    @Override public Object process(InputStream input,Runnable checkpoint) { return new CsvAnalytics().analyze(input,checkpoint); }
}
