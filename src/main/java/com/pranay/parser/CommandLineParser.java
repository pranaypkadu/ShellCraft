package com.pranay.parser;

import com.pranay.parser.model.CmdLine;
import com.pranay.parser.model.Parsed;
import com.pranay.parser.model.ParsedPipe;
import com.pranay.parser.model.ParsedSimple;
import com.pranay.parser.model.RMode;
import com.pranay.parser.model.RSpec;
import com.pranay.parser.model.RStream;
import com.pranay.parser.model.Redirs;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CommandLineParser {
    private static final String PIPE = "|";

    private static final String GT = ">";
    private static final String ONE_GT = "1>";
    private static final String DGT = ">>";
    private static final String ONE_DGT = "1>>";
    private static final String TWO_GT = "2>";
    private static final String TWO_DGT = "2>>";

    public Parsed parse(List<String> tokens) {
        CmdLine base = parseRedirs(tokens);
        Optional<List<List<String>>> split = splitPipeline(base.args());
        return split.isPresent()
                ? new ParsedPipe(split.get(), base.redirs())
                : new ParsedSimple(base);
    }

    private static Optional<List<List<String>>> splitPipeline(List<String> args) {
        boolean saw = false;
        ArrayList<List<String>> stages = new ArrayList<List<String>>();
        ArrayList<String> cur = new ArrayList<String>();

        for (String t : args) {
            if (PIPE.equals(t)) {
                saw = true;
                if (cur.isEmpty()) return Optional.empty();
                stages.add(new ArrayList<String>(cur));
                cur.clear();
            } else {
                cur.add(t);
            }
        }

        if (!saw || cur.isEmpty()) return Optional.empty();
        stages.add(new ArrayList<String>(cur));

        return stages.size() < 2 ? Optional.<List<List<String>>>empty() : Optional.<List<List<String>>>of(stages);
    }

    private static CmdLine parseRedirs(List<String> t) {
        if (t.size() >= 2) {
            String op = t.get(t.size() - 2);
            String file = t.get(t.size() - 1);

            if (GT.equals(op) || ONE_GT.equals(op)) {
                return drop2(t, new Redirs(Optional.of(new RSpec(RStream.STDOUT, Paths.get(file), RMode.TRUNCATE)), Optional.<RSpec>empty()));
            }
            if (DGT.equals(op) || ONE_DGT.equals(op)) {
                return drop2(t, new Redirs(Optional.of(new RSpec(RStream.STDOUT, Paths.get(file), RMode.APPEND)), Optional.<RSpec>empty()));
            }
            if (TWO_GT.equals(op)) {
                return drop2(t, new Redirs(Optional.<RSpec>empty(), Optional.of(new RSpec(RStream.STDERR, Paths.get(file), RMode.TRUNCATE))));
            }
            if (TWO_DGT.equals(op)) {
                return drop2(t, new Redirs(Optional.<RSpec>empty(), Optional.of(new RSpec(RStream.STDERR, Paths.get(file), RMode.APPEND))));
            }
        }
        return new CmdLine(t, Redirs.none());
    }

    private static CmdLine drop2(List<String> t, Redirs r) {
        return new CmdLine(new ArrayList<String>(t.subList(0, t.size() - 2)), r);
    }
}