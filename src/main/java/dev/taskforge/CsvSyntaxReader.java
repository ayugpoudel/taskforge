package dev.taskforge;

import java.io.*;

/** Strict CSV lexical guard: rejects unclosed quotes, embedded unquoted quotes and binary NUL. */
final class CsvSyntaxReader extends FilterReader {
    private boolean fieldStart=true,quoted,afterQuote;
    CsvSyntaxReader(Reader input) { super(input); }
    private void accept(int c) {
        if(c==-1) { if(quoted)throw new CsvAnalytics.InvalidCsv("CSV has an unterminated quoted field.");return; }
        if(c==0)throw new CsvAnalytics.InvalidCsv("CSV contains unsupported binary data.");
        if(quoted) { if(c=='"') { quoted=false;afterQuote=true; }return; }
        if(afterQuote) {
            if(c=='"') { quoted=true;afterQuote=false;return; }
            if(c!=','&&c!='\r'&&c!='\n')throw new CsvAnalytics.InvalidCsv("CSV has invalid quote placement.");
            afterQuote=false;fieldStart=true;return;
        }
        if(c=='"') {
            if(!fieldStart)throw new CsvAnalytics.InvalidCsv("CSV has invalid quote placement.");
            quoted=true;fieldStart=false;
        } else fieldStart=c==','||c=='\r'||c=='\n';
    }
    @Override public int read() throws IOException { int c=in.read();accept(c);return c; }
    @Override public int read(char[] b,int offset,int length) throws IOException {
        int n=in.read(b,offset,length);
        if(n==-1)accept(-1);else for(int i=offset;i<offset+n;i++)accept(b[i]);
        return n;
    }
}
