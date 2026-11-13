package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code const [todos, setTodos] = useState([])} infers {@code never[]} and {@code useState(null)} infers {@code null};
 * the free models rarely repair the type errors that follow. An empty list gets the record type its name implies when
 * that type is imported, else {@code any[]}; an empty id gets {@code string | null}, any other empty value {@code any}.
 */
final class EmptyStateTypes {
    private static final Pattern UNTYPED = Pattern.compile(
            "const\\s*\\[\\s*([A-Za-z_$][\\w$]*)\\s*,\\s*[A-Za-z_$][\\w$]*\\s*]\\s*=\\s*(?:React\\.)?useState\\(\\s*(\\[\\s*]|null)\\s*\\)");

    private static final Pattern UNTYPED_LIST = Pattern.compile(
            "\\b(const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\[\\s*](?!\\s*(?:as|satisfies)\\b)");
    private static final Pattern EMPTY_PROPERTY = Pattern.compile(
            "^(?!\\s*(?:export\\s+)?(?:declare\\s+)?(?:type|interface)\\b)(.*?[{,(]\\s*|\\s+)([A-Za-z_$][\\w$]*)\\s*:\\s*\\[\\s*](?=\\s*(?:[,}]|$))",
            Pattern.MULTILINE);

    private EmptyStateTypes() {
    }

    /** A type error from an empty state: "never[]", "… on type 'never'", or a value refused by a null state. */
    static boolean diagnosed(ProjectDiagnostic diagnostic) {
        String message = diagnostic.message();
        return message != null && (message.contains("never[]") || message.contains("type 'never'")
                || message.contains("SetStateAction<null>") || message.contains("type 'null'"));
    }

    static String fix(String path, String source, Set<String> typeNames) {
        if (!path.matches(".+\\.tsx?"))
            return source;
        source = emptyLists(source, typeNames);
        Matcher untyped = UNTYPED.matcher(source);
        var out = new StringBuilder();
        while (untyped.find()) {
            String type = untyped.group(2).equals("null")
                    ? untyped.group(1).endsWith("Id") ? "string | null" : "any"
                    : listType(untyped.group(1), typeNames, source) + "[]";
            String typed = untyped.group().replaceFirst("useState\\(", "useState<" + type + ">(");
            untyped.appendReplacement(out, Matcher.quoteReplacement(typed));
        }
        return untyped.appendTail(out).toString();
    }

    private static String emptyLists(String source, Set<String> typeNames) {
        Matcher list = UNTYPED_LIST.matcher(source);
        var out = new StringBuilder();
        while (list.find())
            list.appendReplacement(out, Matcher.quoteReplacement(list.group(1) + " " + list.group(2) + ": "
                    + listType(list.group(2), typeNames, source) + "[] = []"));
        Matcher property = EMPTY_PROPERTY.matcher(list.appendTail(out).toString());
        var typed = new StringBuilder();
        while (property.find())
            property.appendReplacement(typed, Matcher.quoteReplacement(property.group(1) + property.group(2) + ": [] as any[]"));
        return property.appendTail(typed).toString();
    }

    /** {@code todos} → {@code Todo}, {@code categories} → {@code Category}, when that name is imported here. */
    private static String listType(String variable, Set<String> typeNames, String source) {
        String lower = variable.toLowerCase(Locale.ROOT);
        String singular = lower.endsWith("ies") ? lower.substring(0, lower.length() - 3) + "y"
                : lower.endsWith("s") ? lower.substring(0, lower.length() - 1) : lower;
        for (String name : typeNames)
            if (name.toLowerCase(Locale.ROOT).equals(singular)
                    && Pattern.compile("import[^;]*\\b" + Pattern.quote(name) + "\\b[^;]*from").matcher(source).find())
                return name;
        return "any";
    }
}
