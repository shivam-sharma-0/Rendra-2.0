package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Deterministic routing reduces authority; message text never grants permission or approves a plan. */
public final class RequestRouter {
    public enum Route { SMALL_TALK, QUESTION, CHANGE, CONTINUE, UNCLEAR }

    private static final Pattern ACTION = Pattern.compile(
            "\\b(build|create|make|develop|implement|add|fix|update|change|modify|edit|remove|delete|"
                    + "design|refactor|rename|connect|integrate|generate|clone|deploy|migrate|optimi[sz]e|"
                    + "test|move|replace|write|banao|bnao|bana do|bna do|badlo|badal do|hatao|hata do|jodo|lagao|theek karo|sudhar|sudharo)\\b");
    private static final Pattern NEGATED = Pattern.compile(
            "\\b(?:do not|don't|dont|never)\\s+(?:build|create|make|implement|change|modify|edit|write|code|add|delete|remove|fix|test)\\b"
                    + "(?:(?!\\bbut\\b)[^,;.!?])*|\\b(?:mat\\s+(?:banao|badlo|karo|likho)|"
                    + "(?:build|change|code|modify|edit|changes?)\\s+(?:mat karo|nahi karna|nahi krna|nahi chahiye))\\b"
                    + "(?:(?!\\blekin\\b)[^,;.!?])*");
    private static final Pattern PLAN_ONLY = Pattern.compile(
            "\\b(?:only plan|plan only|sirf plan|bas plan|pehle plan|first plan|plan first|make a plan|plan an? )\\b");
    private static final Pattern EXPLANATION = Pattern.compile(
            "^(?:(?:just|please|sirf|bas) )?(?:explain|describe|find|locate|show|review|inspect|"
                    + "samjhao|samjha do|batao|btao|kaise|how|why|who|when|where|what|which|kya hai|kya h|"
                    + "kyun|kyu|kon ho|kaun ho|kisne|samjha sakte|bata sakte)\\b|"
                    + "^(?:ye|yeh|yah|tum|aap) (?:kaise|kyun|kya)\\b|"
                    + "\\b(?:samjhao|samjha do|batao|btao)[.!? ]*$");
    private static final Pattern QUESTION_PREFIX = Pattern.compile(
            "^(?:(?:hey|hi|hello)[, !]+)?(?:can|could|would|will) (?:you|u) "
                    + "(?:tell|explain|describe|help me understand|show me how|say|answer)\\b|"
                    + "^kya\\b.*\\b(?:sakte|sakti|sakta)\\b|"
                    + "^(?:can|could) (?:you|u) (?:build|create|make) (?:apps|applications|websites)\\b");
    private static final Pattern INFORMATION_REQUEST = Pattern.compile(
            "^i (?:want|need|would like) (?:to (?:know|understand|learn)|(?:some )?(?:help|advice|information))\\b");
    private static final Pattern CONTRAST_ACTION = Pattern.compile(
            "\\b(?:but|lekin)\\s+(?:please\\s+)?" + ACTION.pattern());
    private static final Pattern IMPERATIVE_CLAUSE = Pattern.compile(
            "(?:\\b(?:and|then|aur|phir|but|lekin)\\s+(?:(?:then|phir)\\s+)?|[,;.!?]\\s*)"
                    + "(?:(?:please|now)\\s+)*(?:(?:can|could|would|will) (?:you|u) )?" + ACTION.pattern()
                    + "(?!\\s+(?:is|are|looks|seems|was|works|failed|failure|great|slow)\\b)");
    private static final Pattern DIRECT_CHANGE = Pattern.compile(
            "^(?:(?:ok(?:ay)?|yes|sure|alright|haan|han)[,!.]? )?"
                    + "(?:(?:please|can you|can u|could you|could u|would you|will you|let's|lets|"
                    + "i want to|i want you to|i would like to) )?" + ACTION.pattern()
                    + "(?!\\s+(?:is|are|looks|seems|was|works|failed|failure|great|slow)\\b)");
    private static final Pattern QUESTION_START = Pattern.compile("^(?:is|are|do|does|did|should|could|can|would|will|kya)\\b");
    private static final Pattern RESUME = Pattern.compile(
            "^(?:(?:ok(?:ay)?|yes|sure|alright|haan|han|h[a]+n)[, ]+)?"
                    + "(?:yes|sure|go ahead(?: and build it)?|do it|build it|make it|create it|build this|make this|"
                    + "go for it|let's do it|lets do it|proceed|build karo|bana do|banao|karo|kar do|continue|"
                    + "start|start karo|shuru karo|aage badho|agla step|next step|next|haan|han|h[a]+n)"
                    + "(?:[, ]+(?:please|now))*[,.!? ]*$");
    private static final Pattern SMALL_TALK = Pattern.compile(
            "^(?:hi+|hey+|hello+|yo|sup|namaste|namaskar|good (?:morning|evening|afternoon|night)|"
                    + "how are (?:you|u)|kaise ho|kya haal hai|sab badhiya|thanks?(?: you)?|thank you|"
                    + "shukriya|dhanyavaad|ok(?:ay)?|cool|nice|great|awesome|perfect|got it|sounds good|"
                    + "bye|goodbye|see ya|test|testing|ping|no|alright|what's up|good job|bahut badhiya|"
                    + "my name is \\p{L}+(?: \\p{L}+){0,2})[,.!? ]*$");
    private static final Pattern APPRECIATION = Pattern.compile(
            "^(?:thanks?|thank you)(?:[,! ]+(?:very|much|a|lot|so|for|your|help|today|i|really|"
                    + "appreciate|all|the|time|you|spent|helping|me|it|that|was|helpful))*[.!? ]*$");
    private static final String APP_NOUN = "(?:to[- ]?do(?: app)?|calculator|counter|timer|quiz|portfolio|dashboard|crm|blog|"
            + "landing page|website|app|application|store|shop)";
    private static final Pattern APP_NEED = Pattern.compile(
            "\\b(?:i|we) (?:need|want) (.+)$|\\bmujhe (.+) chahiye[.!? ]*$");
    private static final Pattern FEATURE_NEED = Pattern.compile(
            "\\b(?:it|this|that) needs? (?:a |an |the )?(?:footer|header|button|navbar|login|dark mode)\\b|"
                    + "\\b(?:header|footer|button|page|navbar) should\\b");
    /** "the home page should show …": a wished-for change, even after a leading "when …". */
    private static final Pattern WISHED_CHANGE = Pattern.compile(
            "\\b(?:should|must|needs? to|has to)\\s+(?:also\\s+)?(?:show|display|have|include|contain|say|list|open|use|support|be)\\b");
    private static final Pattern OBJECT_IMPERATIVE = Pattern.compile(
            "\\b(?:karo|kar do|banao|bana do|bnao|bna do|daal do|hata do|badal do|badlo|lagao|jodo|sudharo)"
                    + "(?: please| now)*[.!? ]*$|^(?:dark mode|login page|footer|header) chahiye[.!? ]*$");
    private static final Pattern BUG = Pattern.compile(
            "\\b(?:broken|blank|error|doesn't work|does not work|not working|not loading|kaam nahi kar raha|"
                    + "kaam nahi karta|khul nahi raha|kuch dikh nahi raha)\\b");
    private static final Pattern PRODUCT_QUESTION = Pattern.compile(
            "^(?:(?:can|could) (?:you|u) (?:tell(?: me)? |explain )?)?how (?:do )?(?:you|u) work[.!? ]*$|"
                    + "^(?:who are (?:you|u)|what can (?:you|u) do|who (?:made|created|built) (?:you|u)|"
                    + "how does (?:rendra|redra) work|what is (?:rendra|redra)|tum kaise kaam karte ho|"
                    + "tum kya kya bana sakte ho)[.!? ]*$");
    private static final Pattern APP_REQUEST = Pattern.compile(
            "^(?:(?:a|an|ek) (?:(?:simple|small|basic|responsive|modern|ecommerce|e-commerce) )*" + APP_NOUN
                    + "(?: app|application|website)?|(?:(?:a|an|ek|simple|small|basic|responsive|modern|ecommerce|e-commerce) )*"
                    + APP_NOUN + " (?:for|with|that|jo|chahiye|banao|bana do)\\b.*)[.!? ]*$");
    private static final Pattern APP_EVALUATION = Pattern.compile(
            "\\b(?:is|are|looks|seems|was|works|kaisa|accha|slow|great)\\b");
    private static final Pattern APPROVED_PLAN = Pattern.compile(
            "^(?:build (?:the )?(?:approved )?plan|implement (?:this|the) plan)[.! ]*$");

    private RequestRouter() {}

    public static Route route(String request) {
        return route(request, List.of(), false);
    }

    public static Route route(String request, List<String> attachments, boolean pendingProposal) {
        return route(ProjectType.BUILD, request, attachments, pendingProposal);
    }

    public static Route route(ProjectType mode, String request, List<String> attachments, boolean pendingProposal) {
        String text = normalize(instructions(request));
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (RESUME.matcher(text).matches()) {
            if (pendingProposal) {
                return Route.CONTINUE;
            }
            return hasAttachments ? Route.UNCLEAR : Route.SMALL_TALK;
        }
        if (!hasAttachments && (SMALL_TALK.matcher(text).matches() || APPRECIATION.matcher(text).matches())) {
            return Route.SMALL_TALK;
        }
        String positive = NEGATED.matcher(text).replaceAll(" ");
        boolean change = imperative(positive);
        if (PLAN_ONLY.matcher(text).find()) {
            return Route.CHANGE;
        }
        if (productQuestion(text) || QUESTION_PREFIX.matcher(text).find() || INFORMATION_REQUEST.matcher(text).find()) {
            return Route.QUESTION;
        }
        // A statement of how the app should behave is a change request, not a question, unless it asks one.
        boolean wished = !text.endsWith("?")
                && (WISHED_CHANGE.matcher(positive).find() || FEATURE_NEED.matcher(positive).find());
        if ((EXPLANATION.matcher(text).find() || QUESTION_START.matcher(text).find())
                && !wished && !DIRECT_CHANGE.matcher(positive).find()
                && !CONTRAST_ACTION.matcher(positive).find()) {
            return Route.QUESTION;
        }
        if (NEGATED.matcher(text).find() && !change) {
            return Route.QUESTION;
        }
        if (change || wished || appNeed(positive) || FEATURE_NEED.matcher(positive).find() || appRequest(text)) {
            return Route.CHANGE;
        }
        if (BUG.matcher(positive).find()) {
            return mode == ProjectType.BUILD ? Route.CHANGE : Route.QUESTION;
        }
        return Route.UNCLEAR;
    }

    public static ProjectType mode(ProjectType requested, String request, List<String> attachments) {
        if (requested == ProjectType.CHAT) {
            return ProjectType.CHAT;
        }
        if (PLAN_ONLY.matcher(normalize(instructions(request))).find()) {
            return ProjectType.PLAN;
        }
        Route route = route(requested, request, attachments, false);
        return route == Route.QUESTION || route == Route.UNCLEAR || route == Route.SMALL_TALK
                ? ProjectType.CHAT : requested;
    }

    /** Quotes and pasted code are context, never the instruction being classified. */
    public static String instructions(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)```.*?```", " ").lines()
                .filter(line -> !line.stripLeading().startsWith(">"))
                .collect(Collectors.joining("\n"));
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace('\u2019', '\'').toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** A bare go-ahead ("ok make it", "yes", "haan") that names no new task of its own. */
    public static boolean confirmation(String request) {
        return RESUME.matcher(normalize(instructions(request))).matches();
    }

    public static boolean buildApprovedPlan(String request) {
        return APPROVED_PLAN.matcher(normalize(instructions(request))).matches();
    }

    public static boolean positiveChange(String request) {
        String positive = NEGATED.matcher(normalize(instructions(request))).replaceAll(" ");
        return imperative(positive);
    }

    private static boolean imperative(String text) {
        return DIRECT_CHANGE.matcher(text.strip()).find() || IMPERATIVE_CLAUSE.matcher(text).find()
                || OBJECT_IMPERATIVE.matcher(text).find();
    }

    private static boolean appRequest(String text) {
        return APP_REQUEST.matcher(text).matches() && !APP_EVALUATION.matcher(text).find();
    }

    private static boolean appNeed(String text) {
        var matcher = APP_NEED.matcher(text);
        if (!matcher.find()) {
            return false;
        }
        String subject = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        return appRequest(subject) || appRequest("a " + subject);
    }

    public static boolean productQuestion(String request) {
        return PRODUCT_QUESTION.matcher(normalize(instructions(request))).matches();
    }

    /** Capability sizing excludes explicitly unwanted services without changing request authority. */
    public static String positiveSignals(String text) {
        String value = normalize(text);
        String data = "(?:database|backend|back-end|auth|authentication|login|signup|accounts|server|api|payments?|cloud|supabase|mongodb|crud|frameworks?|external services?)";
        value = value.replaceAll("\\b(?:without|no|not|don't need|do not need|bina)\\s+(?:a |any |the )?"
                + data + "(?:\\s*(?:,\\s*(?:(?:or|and|aur)\\s+)?|(?:or|and|aur)\\s+)" + data + ")*", " ");
        value = value.replaceAll("\\b" + data + "\\s+(?:nahi chahiye|nahi chaiye|nahi|mat chahiye)\\b", " ");
        return value.replace("ek page", "single page").replace("chhota", "small").replace("chota", "small");
    }
}
