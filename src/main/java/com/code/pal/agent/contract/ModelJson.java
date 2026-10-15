package com.code.pal.agent.contract;

/** Bounded JSON framing; callers still validate the decoded schema and authority. */
public final class ModelJson {
    private ModelJson() {}

    /** Conversational models may surround one object with prose or a Markdown fence. */
    public static String extractObject(String content) {
        requireCapacity(content);
        int start = -1;
        int end = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (depth == 0) {
                if (character == '{') {
                    if (start >= 0) {
                        throw new IllegalArgumentException("Ambiguous model JSON: multiple objects");
                    }
                    start = index;
                    depth = 1;
                }
                continue;
            }
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                end = index + 1;
            }
        }
        if (start < 0 || end < 0 || depth != 0) {
            throw new IllegalArgumentException("Expected one complete JSON object");
        }
        if (content.substring(0, start).stripTrailing().endsWith("[")
                || content.substring(end).stripLeading().startsWith("]")) {
            throw new IllegalArgumentException("Expected an object, not an array");
        }
        return content.substring(start, end);
    }

    /** Contract outputs retain their stricter no-prose policy. */
    public static String object(String content) {
        requireCapacity(content);
        String text = content.strip();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0 || !(text.substring(0, newline).strip().equals("```json")
                    || text.substring(0, newline).strip().equals("```")) || !text.endsWith("```")) {
                throw new IllegalArgumentException("Invalid JSON fence");
            }
            text = text.substring(newline + 1, text.length() - 3).strip();
        }
        if (!text.equals(extractObject(text))) {
            throw new IllegalArgumentException("Expected one JSON object without prose");
        }
        return text;
    }

    private static void requireCapacity(String content) {
        if (content == null || content.length() > 48_000) {
            throw new IllegalArgumentException("Model JSON exceeds capacity");
        }
    }
}
