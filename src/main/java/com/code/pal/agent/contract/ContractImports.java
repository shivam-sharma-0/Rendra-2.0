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
        return "\nAllowed bare imports: " + (contract.stack() == BuildContract.Stack.VITE ? String.join(", ", REACT.stream().sorted().toList()) : "none")
                + ". Other imports must be relative paths to owned files, declared dependency files or these coordinator modules: "
                + String.join(", ", coordinatorPaths(contract))
                + ". Use plain <a href=\"/path\"> navigation with the existing path router. No react-router-dom, lucide-react, "
                + "new packages, Router/Routes/Link wrappers or computed imports. For TS2307 on a disallowed package, remove the import "
                + "and replace its uses with existing React/HTML behavior, not a package install.";
    }

    public static void validate(BuildContract contract, BuildContract.Unit unit, String file, String source) {
        if (source == null || contract.schemaVersion() != 2 || contract.stack() == BuildContract.Stack.MERN
                || !file.matches("(?i).+\\.(?:[cm]?[jt]sx?)$")) {
            return;
        }
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
                        throw new IllegalArgumentException("Computed module imports are not supported by the fixed starter");
                    }
                    requireAllowed(contract, unit, file, tokens.get(index + 2).value());
                } else if (token.value().equals("import") && next.literal()) {
                    requireAllowed(contract, unit, file, next.value());
                } else if (token.value().equals("import") && !next.value().equals(".")) {
                    checkFrom(contract, unit, file, tokens, index + 1);
                }
            } else if (token.value().equals("export") && index + 1 < tokens.size()
                    && Set.of("{", "*", "type").contains(tokens.get(index + 1).value())) {
                checkFrom(contract, unit, file, tokens, index + 1);
            }
        }
    }

    private static void checkFrom(BuildContract contract, BuildContract.Unit unit, String file, List<Token> tokens, int start) {
        for (int index = start; index + 1 < tokens.size(); index++) {
            var token = tokens.get(index);
            if (!token.literal() && Set.of(";", "=", "import", "export").contains(token.value())) {
                return;
            }
            if (!token.literal() && token.value().equals("from") && tokens.get(index + 1).literal()) {
                requireAllowed(contract, unit, file, tokens.get(index + 1).value());
                return;
            }
        }
    }

    private static void requireAllowed(BuildContract contract, BuildContract.Unit unit, String file, String module) {
        if (!(module.startsWith("./") || module.startsWith("../"))) {
            if (contract.stack() == BuildContract.Stack.VITE && REACT.contains(module)) {
                return;
            }
            throw new IllegalArgumentException("Disallowed bare module import; remove it and use existing React and plain anchor navigation");
        }
        Path parent = Path.of(file).getParent();
        String target = BuildContract.canonical((parent == null ? Path.of("") : parent).resolve(module).normalize().toString().replace('\\', '/'));
        var allowed = new HashSet<>(unit.ownedPaths());
        allowed.addAll(unit.imports());
        allowed.addAll(coordinatorPaths(contract));
        if (EXTENSIONS.stream().noneMatch(extension -> allowed.contains(target + extension))) {
            throw new IllegalArgumentException("Relative module import must target an owned file or a declared dependency or coordinator module");
        }
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
