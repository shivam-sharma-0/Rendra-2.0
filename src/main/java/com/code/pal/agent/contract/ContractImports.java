package com.code.pal.agent.contract;

import com.code.pal.project.capability.BuildDecisions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Early module-quality check for fixed v2 starters, not a JavaScript execution/security boundary. */
public final class ContractImports {
    private static final Set<String> REACT = Set.of("react", "react-dom", "react-dom/client", "react/jsx-runtime", "react/jsx-dev-runtime");
    private static final List<String> EXTENSIONS = List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".css", ".json",
            "/index.ts", "/index.tsx", "/index.js", "/index.jsx");

    private record Token(String value, boolean literal) {
    }

    private ContractImports() {
    }

    public static String policy(BuildContract contract) {
        if (contract.schemaVersion() != 2 || contract.stack() == BuildContract.Stack.MERN) {
            return "";
        }
        String navigation = contract.stack() == BuildContract.Stack.VITE
                ? ". Use plain <a href=\"#/path\"> links with the existing hash router; logical contract routes stay /path. "
                : ". Use plain <a href=\"/path\"> navigation with the existing path router. ";
        return "\nAllowed bare imports: " + (contract.stack() == BuildContract.Stack.VITE ? String.join(", ", REACT.stream().sorted().toList()) : "none")
                + ". Relative imports may read owned files in the same or an earlier batch, or these coordinator modules: "
                + String.join(", ", coordinatorPaths(contract))
                + navigation + "No react-router-dom, lucide-react, "
                + "new packages, Router/Routes/Link wrappers or computed imports. For TS2307 on a disallowed package, remove the import "
                + "and replace its uses with existing React/HTML behavior, not a package install.";
    }

    public static Set<String> validate(BuildContract contract, BuildContract.Unit unit, String file, String source) {
        return scan(contract, unit, file, source, true);
    }

    /** Existing failing source may contain invalid imports. Only proven readable targets become repair context. */
    public static Set<String> repairDependencies(BuildContract contract, BuildContract.Unit unit, String file, String source) {
        return scan(contract, unit, file, source, false);
    }

    private static Set<String> scan(BuildContract contract, BuildContract.Unit unit, String file, String source, boolean strict) {
        if (source == null || contract.schemaVersion() != 2 || contract.stack() == BuildContract.Stack.MERN
                || !file.matches("(?i).+\\.(?:[cm]?[jt]sx?)$")) {
            return Set.of();
        }
        var dependencies = new HashSet<String>();
        var tokens = tokens(source);
        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            if (token.literal() || index > 0 && tokens.get(index - 1).value().equals(".")) {
                continue;
            }
            if (Set.of("import", "require").contains(token.value()) && index + 1 < tokens.size()) {
                var next = tokens.get(index + 1);
                if (next.value().equals("(")) {
                    if (index + 3 >= tokens.size() || !tokens.get(index + 2).literal() || !tokens.get(index + 3).value().equals(")")) {
                        if (strict) {
                            throw new IllegalArgumentException("Computed module imports are not supported by the fixed starter");
                        }
                        continue;
                    }
                    add(dependencies, resolve(contract, unit, file, tokens.get(index + 2).value(), strict));
                } else if (token.value().equals("import") && next.literal()) {
                    add(dependencies, resolve(contract, unit, file, next.value(), strict));
                } else if (token.value().equals("import") && !next.value().equals(".")) {
                    add(dependencies, checkFrom(contract, unit, file, tokens, index + 1, strict));
                }
            } else if (token.value().equals("export") && index + 1 < tokens.size()
                    && Set.of("{", "*", "type").contains(tokens.get(index + 1).value())) {
                add(dependencies, checkFrom(contract, unit, file, tokens, index + 1, strict));
            }
        }
        return Set.copyOf(dependencies);
    }

    private static void add(Set<String> dependencies, String path) {
        if (path != null) {
            dependencies.add(path);
        }
    }

    private static String checkFrom(BuildContract contract, BuildContract.Unit unit, String file, List<Token> tokens, int start, boolean strict) {
        for (int index = start; index + 1 < tokens.size(); index++) {
            var token = tokens.get(index);
            if (!token.literal() && Set.of(";", "=", "import", "export").contains(token.value())) {
                return null;
            }
            if (!token.literal() && token.value().equals("from") && tokens.get(index + 1).literal()) {
                return resolve(contract, unit, file, tokens.get(index + 1).value(), strict);
            }
        }
        return null;
    }

    private static String resolve(BuildContract contract, BuildContract.Unit unit, String file, String module, boolean strict) {
        try {
            return requireAllowed(contract, unit, file, module);
        } catch (IllegalArgumentException invalid) {
            if (strict) {
                throw invalid;
            }
            return null;
        }
    }

    private static String requireAllowed(BuildContract contract, BuildContract.Unit unit, String file, String module) {
        if (!(module.startsWith("./") || module.startsWith("../"))) {
            if (contract.stack() == BuildContract.Stack.VITE && REACT.contains(module)) {
                return null;
            }
            throw new IllegalArgumentException("Disallowed bare module import; remove it and use existing React and plain anchor navigation");
        }
        Path parent = Path.of(file).getParent();
        String target = BuildContract.canonical((parent == null ? Path.of("") : parent).resolve(module).normalize().toString().replace('\\', '/'));
        var allowed = availablePaths(contract, unit);
        for (String extension : EXTENSIONS) {
            if (allowed.contains(target + extension)) {
                return target + extension;
            }
        }
        throw new IllegalArgumentException("Relative module import must target an owned earlier or same batch file or a coordinator module");
    }

    /** Read dependencies only. Does not rewrite the reviewed ownership/DAG or grant another file's write scope. */
    public static Set<String> availablePaths(BuildContract contract, BuildContract.Unit unit) {
        var paths = new HashSet<>(unit.ownedPaths());
        if (contract.schemaVersion() == 2) {
            if (!contract.units().contains(unit)) {
                throw new IllegalArgumentException("Foreign unit cannot derive import scope");
            }
            for (var batch : ContractPlans.ordered(contract.batches())) {
                paths.addAll(batch.ownedPaths());
                if (batch.unitIds().contains(unit.id())) {
                    break;
                }
            }
        } else {
            paths.addAll(unit.imports());
        }
        paths.addAll(coordinatorPaths(contract));
        // Approved assets are copied before generation; only their paths, never their binary bodies, are importable.
        contract.assets().forEach(asset -> paths.add(asset.destination()));
        return Set.copyOf(paths);
    }

    private static List<String> coordinatorPaths(BuildContract contract) {
        String root = contract.clientRoot();
        if (contract.stack() == BuildContract.Stack.STATIC) {
            return List.of(root + "theme.css", root + "routes.json");
        }
        var paths = new ArrayList<>(List.of(root + "types.ts", root + "api.ts", root + "theme.css", root + "routes.ts"));
        if (contract.storage() == BuildDecisions.Storage.SUPABASE) {
            paths.add(root + "supabase.ts");
        }
        return paths;
    }

    private static List<Token> tokens(String source) {
        var result = new ArrayList<Token>();
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index + 2);
                index = end < 0 ? source.length() : end + 1;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                int start = ++index;
                while (index < source.length() && source.charAt(index) != current) {
                    index += source.charAt(index) == '\\' ? 2 : 1;
                }
                result.add(new Token(source.substring(start, Math.min(index, source.length())), true));
                index++;
            } else if (Character.isJavaIdentifierStart(current)) {
                int start = index++;
                while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
                    index++;
                }
                result.add(new Token(source.substring(start, index), false));
            } else {
                result.add(new Token(String.valueOf(current), false));
                index++;
            }
        }
        return result;
    }
}
