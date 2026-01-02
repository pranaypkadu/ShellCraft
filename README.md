# ShellCraft 🐚

A **fully functional Unix-like shell** written in Java. Just like bash or zsh, but built from scratch to teach you how shells really work.

---

## What is ShellCraft?

ShellCraft is a working shell that can:
- ✅ Run commands (`ls`, `cat`, `grep`, etc.)
- ✅ Chain commands with pipes (`cat file.txt | grep "keyword"`)
- ✅ Redirect output to files (`echo "hello" > file.txt`)
- ✅ Remember command history
- ✅ Auto-complete commands with TAB
- ✅ Navigate history with arrow keys

---

## Quick Start

### 1. **Clone & Build**
```bash
git clone https://github.com/BackendArchitectX/ShellCraft.git
cd ShellCraft
mvn clean compile
```

### 2. **Run the Shell**
```bash
java -cp target/classes com.pranay.cli.Main
```

### 3. **Start Typing Commands**
```bash
$ pwd
/current/directory

$ echo "Hello World"
Hello World

$ ls | grep .txt
file1.txt
file2.txt
```

That's it! You now have a working shell. 🎉

---

## Built-in Commands

These commands are built right into ShellCraft:

| Command | What It Does | Example |
|---------|-------------|---------|
| `echo` | Print text | `echo Hello World` |
| `pwd` | Show current folder | `pwd` |
| `cd` | Change folder | `cd /tmp` or `cd ~` |
| `exit` | Close the shell | `exit 0` |
| `history` | Show past commands | `history` or `history 10` |
| `type` | Check if command exists | `type echo` |

---

## Cool Features You Can Try

### 💻 Run External Commands
```bash
$ ls -la
$ cat myfile.txt
$ grep "error" logfile.txt
$ python script.py
```

### 🔗 Chain Commands with Pipes
```bash
$ ls -la | grep .java
$ cat file.txt | wc -l
$ ps aux | grep java
```

### 📁 Save Output to Files
```bash
# Save to file (overwrite)
$ echo "Hello" > output.txt

# Add to file (append)
$ echo "World" >> output.txt

# Save errors
$ command 2> errors.txt
```

### ⬆️ Navigate Command History
```bash
# Press the UP arrow key to go to previous commands
# Press the DOWN arrow key to go to next commands
```

### 🎯 Tab Completion
```bash
# Type part of a command and press TAB to auto-complete
$ ec[TAB] → echo
$ ex[TAB] → exit
```

### 💾 Command History
```bash
# See all past commands
$ history

# See last 10 commands
$ history 10

# Load history from file
$ history -r ~/.bash_history

# Save current history to file
$ history -w ~/.bash_history
```

---

## How It Works (Simple Explanation)

1. **You type a command** → `$ echo hello`
2. **Shell parses it** → Breaks into tokens: `["echo", "hello"]`
3. **Shell looks it up** → Finds `echo` in built-in commands
4. **Shell runs it** → Executes and prints: `hello`

For external commands like `ls`:
1. **Shell searches PATH** → Finds `/bin/ls`
2. **Shell spawns process** → Runs the program
3. **Captures output** → Pipes it back to you

For pipes like `cat file.txt | grep keyword`:
1. **Starts cat** → Outputs file contents
2. **Pipes to grep** → Receives input from cat
3. **Filters results** → Shows only matching lines

---

## File Structure (Overview)

```
src/main/java/com/pranay/
├── cli/              ← Main shell loop
├── parser/           ← Breaks input into commands
├── command/          ← Built-in command definitions
├── exec/             ← Runs commands and handles pipes
├── env/              ← Environment variables and PATH
├── history/          ← Command history management
└── input/            ← Tab completion & history navigation
```

---

## System Requirements

- **Java 8 or newer** (check with `java -version`)
- **Maven** (for easy compilation)
- **Linux/Mac/Unix** (for `stty` terminal control)

---

## Troubleshooting

### ❌ "command not found"
```bash
# The command doesn't exist. Try:
$ type ls      # Check if it's installed
$ which ls     # Show full path to command
```

### ❌ Shell won't start
```bash
# Make sure you compiled it first:
mvn clean compile

# Then run with correct classpath:
java -cp target/classes com.pranay.cli.Main
```

### ❌ Can't use TAB completion
```bash
# TAB completion needs a Unix/Linux terminal
# On Windows, use Windows Subsystem for Linux (WSL)
```

---

## Example Workflows

### Workflow 1: Count Lines in Files
```bash
$ cat file.txt | wc -l
42
```

### Workflow 2: Find Java Files
```bash
$ ls -la | grep .java
-rw-r--r-- 1 user group 5234 Main.java
-rw-r--r-- 1 user group 3421 Shell.java
```

### Workflow 3: Save Processed Output
```bash
$ cat data.txt | grep "ERROR" > errors.txt
$ cat errors.txt
ERROR: Connection failed
ERROR: Timeout
```

### Workflow 4: Save Command History
```bash
$ history -w ~/my_commands.txt
$ history -r ~/my_commands.txt
# Now you have your history saved!
```

---

## What You Learn

By exploring ShellCraft's code, you'll understand:

- **How shells parse commands** ← Tokenizer & Parser
- **How pipes work** ← PipelineCommand with Java threads
- **How PATH works** ← PathResolver
- **How to handle I/O redirection** ← Redirection handling
- **How to build concurrent programs** ← Threading and piped streams
- **How to design interactive CLIs** ← Input handling and completion

Perfect for system design interviews and understanding Unix internals.

---

## What ShellCraft DOES NOT Do (Yet)

- ❌ Background jobs (`&`)
- ❌ Environment variables (`$VAR`)
- ❌ Wildcards/glob patterns (`*.txt`)
- ❌ Complex conditionals (`if`, `case`)
- ❌ Shell scripting (loops, functions)

These could be fun additions to try!

---

## Commands Cheat Sheet

```bash
# Navigation
pwd                  # Show current directory
cd /path/to/dir      # Change directory
cd ~                 # Go to home directory

# View files
cat file.txt         # Show file contents
ls -la               # List files with details
grep "text" file     # Search for text

# Create/Edit
echo "hello" > file  # Create file with text
echo "world" >> file # Add more text
touch newfile.txt    # Create empty file

# History
history              # Show all commands
history 10           # Show last 10 commands
↑ / ↓                # Navigate history with arrow keys

# Pipes & Redirection
cmd1 | cmd2          # Pipe output from cmd1 to cmd2
cmd > file           # Save output to file
cmd >> file          # Append output to file
cmd 2> errors        # Save errors to file

# Completion
[TYPE_SOMETHING][TAB] # Auto-complete command
[TAB][TAB]           # Show all options

# Exit
exit                 # Close the shell
exit 1               # Exit with error code
```

---

## Need More Help?

- **See all built-in commands**: `history`, `pwd`, `echo`, `cd`, `exit`, `type`
- **Check if command exists**: `type ls`
- **Learn Unix concepts**: Try common commands like `ls`, `cat`, `grep`, `wc`

---

## Contributing

Found a bug or have an idea? Feel free to:
1. Fork the repository
2. Create a branch with your feature
3. Submit a pull request

---

**Made with ❤️ by Pranay Kadu**  
**Learning resource**: [CodeCrafters - Build Your Own Shell](https://app.codecrafters.io/courses/shell/overview)

Happy shell scripting! 🚀
