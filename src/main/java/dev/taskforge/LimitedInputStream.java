package dev.taskforge;

import java.io.*;

/** Enforce a byte limit even if an object was changed outside TaskForge. */
final class LimitedInputStream extends FilterInputStream {
  final long limit;
  long count;

  LimitedInputStream(InputStream input, long limit) {
    super(input);
    this.limit = limit;
  }

  private void observed(int n) {
    if (n > 0 && (count += n) > limit)
      throw new CsvAnalytics.InvalidCsv("Input exceeds the byte limit.");
  }

  @Override
  public int read() throws IOException {
    int n = in.read();
    observed(n < 0 ? 0 : 1);
    return n;
  }

  @Override
  public int read(byte[] b, int offset, int length) throws IOException {
    int n = in.read(b, offset, length);
    observed(n);
    return n;
  }
}
