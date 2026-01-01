package com.pranay.exec;

import java.io.IOException;
import java.io.PrintStream;

public interface OutTarget extends AutoCloseable {
    PrintStream ps();
    @Override void close() throws IOException;
}
