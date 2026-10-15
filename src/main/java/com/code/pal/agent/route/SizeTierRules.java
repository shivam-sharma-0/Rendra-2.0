package com.code.pal.agent.route;

import com.code.pal.project.domain.ProjectType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.project.capability.BuildDecisions;

/**
 * Decides a run's {@link SizeTier} from the request and whether the project already has code — by rules,
 * no model call. The words counted are the pages or screens named, data or server needs, and words that
 * name a large kind of app; "simple", "single page" and the like pull the other way. A wrong guess only
 * changes the run's limits (today's loop runs either way).
 */
public final class SizeTierRules {

    private static final Pattern PAGE_WORDS = words("dashboard", "admin", "profile", "settings", "checkout", "cart",
            "login", "log in", "sign in", "signup", "sign up", "register", "landing", "about us", "about page", "contact",
            "pricing", "blog", "gallery", "catalog", "catalogue", "orders", "inventory", "reports", "analytics", "calendar",
            "faq", "portfolio", "careers", "booking", "favorites", "wishlist", "notifications", "inbox");
    private static final Pattern PAGE_COUNT = Pattern.compile(
            "\\b(\\d+|two|three|four|five|six|seven|eight|nine|ten|several|multiple|many)\\s+(?:\\w+\\s+)?(?:pages|screens|views|sections)\\b");
    private static final Pattern DATA_WORDS = words("database", "backend", "back-end", "server", "api", "auth",
            "authentication", "login", "log in", "sign in", "sign up", "signup", "accounts", "user accounts", "users", "roles",
            "payment", "payments", "stripe", "supabase", "mongo", "mongodb", "postgres", "mysql", "crud", "upload", "uploads",
            "realtime", "real-time", "cloud");
    private static final Pattern LARGE_WORDS = words("full-stack", "full stack", "fullstack", "platform", "marketplace",
            "e-commerce", "ecommerce", "online store", "saas", "crm", "erp", "management system", "social network",
            "multi-page", "multipage", "portal", "admin panel", "complete app");
    private static final Pattern SMALL_WORDS = words("single page", "single-page", "one page", "one-page", "landing page",
            "simple", "small", "tiny", "basic", "calculator", "counter", "timer", "todo", "to-do", "quiz");
    private static final Pattern REBUILD_WORDS = words("from scratch", "rebuild", "start over", "redo the whole",
            "rewrite the whole", "new app", "new website");
    private static final Map<String, Integer> NUMBERS = Map.of("two", 2, "three", 3, "four", 4, "five", 5, "six", 6,
            "seven", 7, "eight", 8, "nine", 9, "ten", 10);
    /** Marks the build prompt written when requirements are approved: only its answers carry the user's choices. */
    private static final String APPROVED_REQUIREMENTS = "\nApproved requirements:\n";

    private SizeTierRules() {
    }

    /**
     * @param request         the request the run answers
     * @param initialPrompt   the project's first prompt (read with an approved-requirements request, whose
     *                        own text is mostly questions)
     * @param existingProject whether the project already has code (more than its brief and uploads)
     */
    public static Decision decide(ProjectType mode, String request, String initialPrompt, boolean existingProject) {
        if (mode != ProjectType.BUILD) return new Decision(SizeTier.S, List.of("not a build"));
        String text = RequestRouter.positiveSignals(signalText(request, initialPrompt));
        Set<String> pages = matches(PAGE_WORDS, text);
        int pageCount = Math.max(pages.size(), namedPageCount(text));
        Set<String> data = matches(DATA_WORDS, text);
        Set<String> large = matches(LARGE_WORDS, text);
        Set<String> small = matches(SMALL_WORDS, text);
        boolean rebuild = !matches(REBUILD_WORDS, text).isEmpty();

        List<String> reasons = new ArrayList<>();
        if (pageCount > 0) reasons.add(pageCount + (pageCount == 1 ? " page or screen" : " pages or screens") + (pages.isEmpty() ? "" : " " + pages));
        if (!data.isEmpty()) reasons.add("data or server " + data);
        if (!large.isEmpty()) reasons.add("large app " + large);
        if (!small.isEmpty()) reasons.add("small " + small);

        if (existingProject && !rebuild) {
            reasons.addFirst("change to an existing project");
            boolean feature = pageCount >= 3 || !large.isEmpty() || (!data.isEmpty() && pageCount >= 2)
                    || text.matches(".*\\b(crud|signup|sign up|user accounts|roles|authentication|mongodb|supabase)\\b.*");
            return new Decision(feature ? SizeTier.M : SizeTier.S, reasons);
        }
        reasons.addFirst(existingProject ? "rebuild" : "new project");
        int score = pageCount + (data.isEmpty() ? 0 : 2) + (large.isEmpty() ? 0 : 3) + (text.length() > 1_200 ? 1 : 0)
                - (small.isEmpty() ? 0 : 2);
        SizeTier tier = score >= 6 || (pageCount >= 3 && !data.isEmpty()) ? SizeTier.L : score >= 2 ? SizeTier.M : SizeTier.S;
        return new Decision(tier, reasons);
    }
    public static Decision decide(ProjectType mode,String request,String initialPrompt,boolean existingProject,
                                   BuildDecisions.Choice choice) {
        var result=decide(mode,request,initialPrompt,existingProject);
        if(mode==ProjectType.BUILD && !existingProject && result.tier()==SizeTier.S && choice!=null
                && (choice.auth()==BuildDecisions.Auth.ACCOUNTS
                || choice.storage()==BuildDecisions.Storage.MERN
                || choice.storage()==BuildDecisions.Storage.SUPABASE)) {
            var reasons=new ArrayList<>(result.reasons());reasons.add("approved server/auth capability");return new Decision(SizeTier.M,reasons);
        }
        return result;
    }

    /** The tier and the signals that led to it (shown in diagnostics, not to the user). */
    public record Decision(SizeTier tier, List<String> reasons) {
        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    private static String signalText(String request, String initialPrompt) {
        String value = request == null ? "" : request;
        int marker = value.indexOf(APPROVED_REQUIREMENTS);
        if (marker < 0) return value;
        // "- question: answer" lines; the questions mention data, pages and so on whatever the answer is.
        StringBuilder answers = new StringBuilder(initialPrompt == null ? "" : initialPrompt);
        for (String line : value.substring(marker + APPROVED_REQUIREMENTS.length()).split("\n")) {
            int separator = line.lastIndexOf(": ");
            if (line.startsWith("- ") && separator > 0) answers.append('\n').append(line.substring(separator + 2));
        }
        return answers.toString();
    }

    private static int namedPageCount(String text) {
        int best = 0;
        Matcher matcher = PAGE_COUNT.matcher(text);
        while (matcher.find()) {
            String count = matcher.group(1);
            int value = Character.isDigit(count.charAt(0)) ? Integer.parseInt(count.length() > 2 ? "99" : count)
                    : NUMBERS.getOrDefault(count, 3);
            best = Math.max(best, Math.min(value, 20));
        }
        return best;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private static Pattern words(String... words) {
        List<String> quoted = new ArrayList<>();
        for (String word : words) quoted.add(Pattern.quote(word));
        // Longest first, so "landing page" wins over "landing".
        quoted.sort((a, b) -> b.length() - a.length());
        return Pattern.compile("(?<![a-z0-9-])(?:" + String.join("|", quoted) + ")(?![a-z0-9-])");
    }
}
