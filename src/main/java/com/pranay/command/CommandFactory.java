package com.pranay.command;

import java.util.List;

public interface CommandFactory {
    Cmd create(String name, List<String> argv);
}