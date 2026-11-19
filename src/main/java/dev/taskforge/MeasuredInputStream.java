package dev.taskforge;

import java.io.*;

/** Measures time blocked in source reads, separately from CSV calculation. */
final class MeasuredInputStream extends FilterInputStream {
  long readNanos;

  MeasuredInputStream(InputStream input) {
    super(input);
  }

  @Override
  public int read() throws IOException {
    long start = System.nanoTime();
    try {
      return in.read();
    } finally {
      readNanos += System.nanoTime() - start;
    }
  }

  @Override
  public int read(byte[] b, int offset, int length) throws IOException {
    long start = System.nanoTime();
    try {
      return in.read(b, offset, length);
    } finally {
      readNanos += System.nanoTime() - start;
    }
  }
}
