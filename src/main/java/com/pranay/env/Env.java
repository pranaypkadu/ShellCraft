package com.pranay.env;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class Env {
    private static final String ENV_PATH = "PATH";
    private static final String ENV_HOME = "HOME";

    private Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    public Path cwd() { return cwd; }
    public void cwd(Path p) { cwd = p.toAbsolutePath().normalize(); }

    public String home() { return System.getenv(ENV_HOME); }

    public List<Path> pathDirs() {
        String s = System.getenv(ENV_PATH);
        if (s == null || s.isEmpty()) return new ArrayList<Path>();

        char sep = File.pathSeparatorChar;
        ArrayList<Path> out = new ArrayList<Path>();
        int start = 0;

        for (int i = 0, n = s.length(); i <= n; i++) {
            if (i == n || s.charAt(i) == sep) {
                if (i > start) out.add(Paths.get(s.substring(start, i)));
                start = i + 1;
            }
        }
        return out;
    }
}