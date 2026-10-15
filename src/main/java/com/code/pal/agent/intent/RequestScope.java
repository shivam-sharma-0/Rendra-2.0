package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Cheap scope rules reduce authority; they never approve a plan or grant new tools. */
public final class RequestScope {
    private static final Pattern ACTION=Pattern.compile("\\b(build|create|make|implement|add|fix|update|change|remove|delete|design|refactor|rename|connect|integrate|banao|bnao|bana do|bna do|badlo|hatao|jodo|theek|sudhar|shuru)\\b");
    private static final Pattern NEGATED=Pattern.compile("\\b(?:do not|don't|dont|never)\\s+(?:build|create|make|implement|change|modify|edit|write|code|add|delete|remove)\\b(?:(?!\\bbut\\b)[^,;.!?])*|\\b(?:mat\\s+(?:banao|badlo|karo|likho)|(?:build|change|code|modify|edit|changes?)\\s+(?:mat karo|nahi karna|nahi krna|nahi chahiye))\\b(?:(?!\\blekin\\b)[^,;.!?])*");
    private static final Pattern PLAN_ONLY=Pattern.compile("\\b(?:only plan|plan only|sirf plan|bas plan|pehle plan|first plan|plan first)\\b");
    private static final Pattern READ_ONLY=Pattern.compile("\\b(?:only explain|just explain|sirf samjhao|bas samjhao|sirf analyse|only analy[sz]e|only review|just review|sirf check|bas check)\\b");
    private static final Pattern RESUME=Pattern.compile("^(?:ok(?:ay)?\\s+)?(?:yes|sure|go ahead|continue|start|start karo|shuru karo|aage badho|agla step|next|haan|han|h[a]+n)[.! ]*$");
    private RequestScope() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).replace('\u2019','\'').toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").strip();
    }
    public static ProjectType mode(ProjectType requested,String request) {
        if(requested==ProjectType.CHAT)return requested;
        String text=normalize(request),positive=NEGATED.matcher(text).replaceAll(" ");
        if(PLAN_ONLY.matcher(text).find())return ProjectType.PLAN;
        if(READ_ONLY.matcher(text).find() && !text.matches(".*\\b(?:but|lekin)\\b.*\\b(?:add|fix|build|change|implement|banao|badlo)\\b.*"))return ProjectType.CHAT;
        if(NEGATED.matcher(text).find() && !ACTION.matcher(positive).find())return ProjectType.CHAT;
        return requested;
    }
    public static boolean continuation(String request,ProjectType mode,boolean pendingTask) {
        return mode!=ProjectType.CHAT && pendingTask && RESUME.matcher(normalize(request)).matches();
    }
    public static boolean action(String request) {return ACTION.matcher(normalize(request)).find();}
    public static String positiveSignals(String text) {
        String value=normalize(text);
        String data="(?:database|backend|back-end|auth|authentication|login|signup|accounts|server|api|payments?|cloud|supabase|mongodb|crud)";
        value=value.replaceAll("\\b(?:without|no|not|don't need|do not need|bina)\\s+(?:a |any |the )?"+data+"(?:\\s*(?:,|or|and|aur)\\s*"+data+")*", " ");
        value=value.replaceAll("\\b"+data+"\\s+(?:nahi chahiye|nahi chaiye|nahi|mat chahiye)\\b"," ");
        return value.replace("ek page","single page").replace("chhota","small").replace("chota","small");
    }
}
