package com.pranay.command;

import com.pranay.builtin.Cd;
import com.pranay.builtin.Echo;
import com.pranay.builtin.Exit;
import com.pranay.builtin.History;
import com.pranay.builtin.Pwd;
import com.pranay.builtin.Type;
import com.pranay.env.PathResolver;
import com.pranay.util.Lists;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuiltinRegistry {
    private final Map<String, Cmd> map;

    public BuiltinRegistry(PathResolver resolver) {
        LinkedHashMap<String, Cmd> tmp = new LinkedHashMap<String, Cmd>();
        tmp.put("exit", new Exit());
        tmp.put("echo", new Echo());
        tmp.put("pwd", new Pwd());
        tmp.put("cd", new Cd());
        tmp.put("history", new History());
        tmp.put("type", new Type(this, resolver));
        this.map = Collections.unmodifiableMap(tmp);
    }

    public Cmd get(String name) { return map.get(name); }
    public boolean isBuiltin(String name) { return map.containsKey(name); }
    public List<String> names() { return Lists.copyOfNoNulls(map.keySet()); }
}