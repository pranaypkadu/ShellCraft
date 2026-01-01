package com.pranay.input;

import com.pranay.util.IOUtil;

import java.io.InputStream;

public final class TerminalMode {
    private String prev;

    public boolean enableRawMode() throws Exception {
        if (System.console() == null) return false;
        prev = exec("sh", "-c", "stty -g < /dev/tty").trim();
        exec("sh", "-c", "stty -icanon -echo min 1 time 0 < /dev/tty");
        return true;
    }

    public void disableRawMode() throws Exception {
        if (prev == null) return;
        exec("sh", "-c", "stty " + prev + " < /dev/tty");
    }

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (InputStream is = p.getInputStream()) {
            byte[] b = IOUtil.readAllBytes(is);
            p.waitFor();
            return new String(b);
        }
    }
}