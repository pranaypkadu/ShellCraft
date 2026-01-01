package com.pranay.env;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class PathResolver {
    private final Env env;

    public PathResolver(Env env) { this.env = env; }

    public Optional<Path> findExecutable(String name) {
        if (hasSep(name)) {
            Path p = Paths.get(name);
            if (!p.isAbsolute()) p = env.cwd().resolve(p).normalize();
            return (Files.isRegularFile(p) && Files.isExecutable(p)) ? Optional.of(p) : Optional.<Path>empty();
        }

        for (Path dir : env.pathDirs()) {
            Path c = dir.resolve(name);
            if (Files.isRegularFile(c) && Files.isExecutable(c)) return Optional.of(c);
        }
        return Optional.empty();
    }

    public Set<String> execNamesByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || hasSep(prefix)) return Collections.emptySet();

        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (Path dir : env.pathDirs()) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = (p.getFileName() == null) ? null : p.getFileName().toString();
                    if (n == null || !n.startsWith(prefix)) continue;
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) out.add(n);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static boolean hasSep(String s) {
        if (s.indexOf('/') >= 0) return true;
        if (File.separatorChar == '\\' && s.indexOf('\\') >= 0) return true;
        return false;
    }
}