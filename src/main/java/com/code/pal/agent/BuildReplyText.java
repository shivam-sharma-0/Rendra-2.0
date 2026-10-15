package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import java.util.regex.Pattern;

/** Reuses generation text without another call. Outcome claims always come from backend state. */
final class BuildReplyText {
    private static final Pattern TECHNICAL=Pattern.compile("```|(?:[\\w.-]+/)+[\\w.-]+|\\b[\\w.-]+\\.(?:tsx?|jsx?|json|css|html|sql|ya?ml|md)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTCOME=Pattern.compile("\\b(?:live|published|deployed|production|ready|validated|saved|passed)\\b|end.to.end",Pattern.CASE_INSENSITIVE);
    private BuildReplyText() {}
    static String success(String proposed,AgentRun run) {
        String text=proposed==null?"":proposed.split("(?i)\\n\\s*(?:changed files|files changed|created files|modified files)\\s*:",2)[0];
        text=text.lines().filter(line->!TECHNICAL.matcher(line).find() && !OUTCOME.matcher(line).find()).limit(6).collect(java.util.stream.Collectors.joining("\n")).strip();
        if(text.length()>600)text="";
        if(text.isBlank())text="Updated your application.";
        String preview=switch(run.getPreviewStatus()==null?"":run.getPreviewStatus()) {
            case "READY" -> "Your changes are saved. The preview is ready.";
            case "FAILED" -> "Your changes are saved, but the preview could not be prepared. Open Details to review the problem.";
            case "BUILDING" -> "Your changes are saved. Preparing the preview.";
            default -> "Your changes are saved. The preview is not ready yet.";
        };
        return text+"\n\n"+preview;
    }
}
