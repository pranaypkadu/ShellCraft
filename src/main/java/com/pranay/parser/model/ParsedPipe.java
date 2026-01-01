package com.pranay.parser.model;

import com.pranay.util.Lists;

import java.util.ArrayList;
import java.util.List;

public final class ParsedPipe implements Parsed {
    private final List<List<String>> stages;
    private final Redirs redirs;

    public ParsedPipe(List<List<String>> stages, Redirs redirs) {
        ArrayList<List<String>> tmp = new ArrayList<List<String>>(stages.size());
        for (List<String> s : stages) tmp.add(Lists.copyOfNoNulls(s));
        this.stages = Lists.copyOfNoNulls(tmp);
        this.redirs = redirs;
    }

    public List<List<String>> stages() {
        return stages;
    }

    public Redirs redirs() {
        return redirs;
    }
}