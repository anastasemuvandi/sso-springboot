package rw.gov.risa.sso.wrapper.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal option parser for the ops commands: {@code --flag}, {@code --key value}, {@code --key=value};
 * repeatable options collect every value. Anything not declared is reported by {@link #unknown()} (the
 * command answers 64, the usage-error exit code).
 */
public final class CliArgs {

    private final Set<String> flags = new LinkedHashSet<>();
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> unknown = new ArrayList<>();

    private CliArgs() {
    }

    /**
     * @param flagNames   boolean options (without {@code --})
     * @param optionNames options that take a value (without {@code --})
     */
    public static CliArgs parse(List<String> args, Set<String> flagNames, Set<String> optionNames) {
        CliArgs a = new CliArgs();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--")) {
                a.unknown.add(arg);
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (flagNames.contains(name) && value == null) {
                a.flags.add(name);
            } else if (optionNames.contains(name)) {
                if (value == null) {
                    if (i + 1 >= args.size()) {
                        a.unknown.add(arg + " (missing value)");
                        continue;
                    }
                    value = args.get(++i);
                }
                a.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            } else {
                a.unknown.add(arg);
            }
        }
        return a;
    }

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    public Optional<String> value(String option) {
        List<String> v = values.get(option);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(v.size() - 1));
    }

    public List<String> all(String option) {
        return List.copyOf(values.getOrDefault(option, List.of()));
    }

    public List<String> unknown() {
        return List.copyOf(unknown);
    }
}
