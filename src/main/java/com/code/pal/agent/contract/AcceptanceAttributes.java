package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A FILL check selects input[name='title'] while units often render id="title" only. When exactly one owned form
 * control carries that id and no name, and no owned file already names it, the control gets name="title"; nothing else
 * is guessed. */
final class AcceptanceAttributes {
    private static final Pattern FIELD = Pattern.compile("(input|textarea|select)\\[name=['\"]([A-Za-z][\\w-]{0,63})['\"]]");
    private static final Pattern BUTTON = Pattern.compile("button\\[data-action=['\"]([A-Za-z][\\w-]{0,63})['\"]]");
    private static final Pattern SUBMIT = Pattern.compile("\\btype=\\{?\\s*['\"]submit['\"]");

    private AcceptanceAttributes() {}

    /** Returns the sources with names added, keyed like {@code sources}; unchanged files keep their text. */
    static Map<String, String> nameFields(List<BehaviorCriteria.BrowserAction> assigned, Map<String, String> sources) {
        var out = new LinkedHashMap<>(sources);
        for (var action : assigned) {
            var field = action.kind() == BehaviorCriteria.BrowserKind.FILL && action.selector() != null
                    ? FIELD.matcher(action.selector().trim()) : null;
            if (field == null || !field.matches()) continue;
            String tag = field.group(1), name = field.group(2);
            var named = Pattern.compile("\\bname=\\{?\\s*['\"]" + Pattern.quote(name) + "['\"]");
            if (out.values().stream().anyMatch(source -> named.matcher(source).find())) continue;
            var id = Pattern.compile("\\bid=\\{?\\s*['\"]" + Pattern.quote(name) + "['\"]");
            String owner = null;
            int at = -1, matches = 0;
            for (var entry : out.entrySet()) {
                String source = entry.getValue();
                for (var open = Pattern.compile("<" + tag + "\\b").matcher(source); open.find();) {
                    String attributes = source.substring(open.end(), end(source, open.end()));
                    if (id.matcher(attributes).find() && !attributes.matches("(?s).*\\bname=.*")) {
                        matches++; owner = entry.getKey(); at = open.end();
                    }
                }
            }
            if (matches != 1) continue;
            String source = out.get(owner);
            out.put(owner, source.substring(0, at) + " name=\"" + name + "\"" + source.substring(at));
        }
        return out;
    }

    /** A CLICK check selects button[data-action='save'] while units often render a plain submit button. When no owned
     * file declares that action, and the file holding the field of the FILL just before the click has exactly one
     * explicit type="submit" button without a data-action, that button gets data-action="save"; nothing else is guessed. */
    static Map<String, String> actionButtons(List<BehaviorCriteria.BrowserAction> assigned, Map<String, String> sources) {
        var out = new LinkedHashMap<>(sources);
        String field = null;
        for (var action : assigned) {
            if (action.kind() == BehaviorCriteria.BrowserKind.FILL && action.selector() != null) {
                var fill = FIELD.matcher(action.selector().trim());
                field = fill.matches() ? fill.group(2) : null;
                continue;
            }
            var button = action.kind() == BehaviorCriteria.BrowserKind.CLICK && action.selector() != null
                    ? BUTTON.matcher(action.selector().trim()) : null;
            String prepared = field;
            field = null;
            if (button == null || !button.matches() || prepared == null) continue;
            String name = button.group(1);
            var declared = Pattern.compile("\\bdata-action=\\{?\\s*['\"]" + Pattern.quote(name) + "['\"]");
            if (out.values().stream().anyMatch(source -> declared.matcher(source).find())) continue;
            var named = Pattern.compile("\\bname=\\{?\\s*['\"]" + Pattern.quote(prepared) + "['\"]");
            String owner = null;
            int at = -1, matches = 0;
            for (var entry : out.entrySet()) {
                String source = entry.getValue();
                if (!named.matcher(source).find()) continue;
                for (var open = Pattern.compile("<button\\b").matcher(source); open.find();) {
                    String attributes = source.substring(open.end(), end(source, open.end()));
                    if (SUBMIT.matcher(attributes).find() && !attributes.contains("data-action")) {
                        matches++; owner = entry.getKey(); at = open.end();
                    }
                }
            }
            if (matches != 1) continue;
            String source = out.get(owner);
            out.put(owner, source.substring(0, at) + " data-action=\"" + name + "\"" + source.substring(at));
        }
        return out;
    }

    /** End of a JSX opening tag: the first '>' outside braces and quotes (arrow functions inside {...} are skipped). */
    private static int end(String source, int from) {
        int depth = 0;
        char quote = 0;
        for (int index = from; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) { if (current == quote) quote = 0; }
            else if (current == '"' || current == '\'' || current == '`') quote = current;
            else if (current == '{') depth++;
            else if (current == '}') depth--;
            else if (current == '>' && depth == 0) return index;
        }
        return source.length();
    }
}
