package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Ground pre-code selectors in named implementation targets; known non-route links become href checks. */
public final class SpecAcceptance {
    private static final Pattern HREF = Pattern.compile("\\[\\s*href\\s*(=|\\^=|\\*=)\\s*(['\"])(.*?)\\2\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE = Pattern.compile("(?::(?:checked|disabled|enabled|empty|valid|invalid)|:not\\(:(?:checked|disabled|enabled)\\))$");

    public record Target(String testId, String originalSelector) {
    }

    record Compiled(BehaviorCriteria criteria, List<Target> targets) {
    }

    private SpecAcceptance() {
    }

    public static String policy() {
        return "\nRender the contract's data-testid hooks on the real semantic elements described by acceptanceTargets. "
                + "Use space-separated data-testid tokens when one element has multiple targets. Preserve meaningful original ids/roles/attributes. "
                + "State selectors such as :checked must reflect real native state, not hard-coded test results or hidden duplicate elements. "
                + "Link checks assert href without visiting external/mailto/fragment destinations; internal route flows still navigate. "
                + "The compiled acceptance is authoritative when an original selector suggests a different destination.";
    }

    static Compiled compile(ApprovedSpec approved) {
        var actions = new ArrayList<BrowserAction>();
        var targets = new LinkedHashMap<String, Target>();
        var routes = approved.spec().pages().stream().map(ApprovedSpec.Page::path).toList();
        for (var scenario : approved.spec().acceptance()) {
            if (!"BROWSER".equals(scenario.kind())) {
                throw new IllegalArgumentException("Required connector acceptance needs its approved adapter");
            }
            var positiveSelectors = new HashSet<String>();
            for (int index = 0; index < scenario.steps().size(); index++) {
                var step = scenario.steps().get(index);
                String id = scenario.id() + "_" + index;
                var kind = BrowserKind.valueOf(step.kind());
                var next = index + 1 < scenario.steps().size() ? scenario.steps().get(index + 1) : null;
                boolean zeroCount = kind == BrowserKind.ASSERT_COUNT && step.value() != null && step.value().matches("0+");
                boolean unprovedAbsence = zeroCount
                        && !positiveSelectors.contains(step.selector());
                String selector = unprovedAbsence ? step.selector() : selector(scenario.id(), id, step.selector(), targets);
                if (kind == BrowserKind.ASSERT_TEXT || kind == BrowserKind.ASSERT_COUNT && !zeroCount) {
                    positiveSelectors.add(step.selector());
                }
                boolean link = kind == BrowserKind.CLICK && anchor(step.selector());
                if (!link) {
                    actions.add(new BrowserAction(id, kind, selector, step.value()));
                    continue;
                }
                var href = HREF.matcher(step.selector());
                boolean explicit = href.find();
                String destination = explicit ? href.group(3) : null;
                String operator = explicit ? href.group(1) : "=";
                if (!explicit && step.value() != null && step.value().matches("(?is)^(?:[/#]|[a-z][a-z0-9+.-]*:).*")) {
                    destination = step.value();
                }
                String followingRoute = next != null && next.kind().equals("ASSERT_ROUTE") ? next.value() : null;
                String internal = null;
                String logical = approved.desired().renderStack() == ApprovedSpec.RenderStack.VITE
                        && destination != null && destination.startsWith("#/") ? destination.substring(1) : destination;
                if (logical != null && operator.equals("=") && routes.contains(logical)) {
                    internal = logical;
                } else if (destination == null && followingRoute != null && routes.contains(followingRoute)) {
                    internal = followingRoute;
                }
                boolean needsDestination = scenario.steps().subList(index + 1, scenario.steps().size()).stream()
                        .anyMatch(later -> !later.kind().equals("ASSERT_ROUTE"));
                if (internal != null && needsDestination) {
                    // Destination UI steps need a real same-project navigation, not a link-presence substitute.
                    actions.add(new BrowserAction(id, BrowserKind.CLICK,
                            "a" + selector + "[href=" + quoted(internalHref(approved, internal)) + "]", null));
                    continue;
                }
                if (destination == null && internal != null) {
                    destination = internal;
                }
                String predicate = safeLinks();
                if (destination != null) {
                    String target = destination;
                    if (target.isBlank() || Set.of("#", "mailto:", "tel:").contains(target)
                            || target.matches("(?is)^(?:javascript|data|vbscript):.*") || target.startsWith("//")) {
                        throw new IllegalArgumentException("Navigation acceptance has an unsafe or empty link destination");
                    }
                    String routeTarget = approved.desired().renderStack() == ApprovedSpec.RenderStack.VITE && target.startsWith("#/")
                            ? target.substring(1) : target;
                    if (operator.equals("=") && routeTarget.startsWith("/") && !routeTarget.contains("#") && !routes.contains(routeTarget)) {
                        throw new IllegalArgumentException("Internal navigation acceptance needs an approved page destination");
                    }
                    String hrefTarget = target;
                    if (internal != null) {
                        hrefTarget = internalHref(approved, internal);
                    } else if (target.startsWith("/")) {
                        hrefTarget = internalHref(approved, target);
                    }
                    predicate = "[href" + operator + quoted(hrefTarget) + "]";
                    boolean relative = !target.matches("(?is)^[a-z][a-z0-9+.-]*:.*") && !target.startsWith("#");
                    if (!operator.equals("=") && relative && needsDestination) {
                        throw new IllegalArgumentException("Partial internal navigation needs an exact approved destination before UI interactions");
                    }
                    if (!operator.equals("=") && !relative && !target.startsWith("mailto:") && !target.startsWith("tel:")) {
                        predicate += safeLinks();
                    }
                }
                String linkSelector = "a" + selector + predicate;
                actions.add(new BrowserAction(id, BrowserKind.ASSERT_COUNT, linkSelector, "1"));
                if (followingRoute != null) {
                    // The model invented a route after a mailto/external/fragment link. Prove its href instead.
                    // Keep the action id/ownership; do not pretend that an unapproved page was visited.
                    index++;
                    actions.add(new BrowserAction(scenario.id() + "_" + index, BrowserKind.ASSERT_COUNT, linkSelector, "1"));
                }
            }
        }
        return new Compiled(new BehaviorCriteria(1, actions, List.of(), false, false), List.copyOf(targets.values()));
    }

    public static List<Target> targets(BuildContract contract, ApprovedSpec spec, Set<String> actionIds) {
        if (spec == null || contract.schemaVersion() != 2) {
            return List.of();
        }
        if (contract.acceptance().browser().stream().noneMatch(action -> action.selector() != null
                && action.selector().contains("[data-testid~=\"rendra_"))) {
            return List.of();
        }
        var compiled = compile(spec);
        // Historical reviewed contracts keep their original selectors, never get silently rebound to new hooks.
        if (!contract.acceptance().browser().containsAll(compiled.criteria().browser())) {
            return List.of();
        }
        return compiled.targets().stream().filter(target -> contract.acceptance().browser().stream()
                .anyMatch(action -> actionIds.contains(action.id()) && action.selector() != null
                        && action.selector().contains("[data-testid~=" + quoted(target.testId()) + "]"))).toList();
    }

    private static String safeLinks() {
        // Internal routes need an explicit approved destination, handled above. Do not enumerate every page here.
        return ":is([href^=\"#\"],[href^=\"mailto:\"],[href^=\"tel:\"],[href^=\"https://\"],[href^=\"http://\"])"
                + ":not([href=\"#\"]):not([href=\"mailto:\"]):not([href=\"tel:\"])";
    }

    /** Strip attribute/string/pseudo-function contents before inspecting the final compound's type. */
    private static boolean anchor(String selector) {
        if (selector == null) {
            return false;
        }
        var structural = new StringBuilder();
        int brackets = 0;
        int parentheses = 0;
        char quote = 0;
        for (int index = 0; index < selector.length(); index++) {
            char current = selector.charAt(index);
            if (quote != 0) {
                if (current == '\\') {
                    index++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[') {
                brackets++;
            } else if (current == ']') {
                brackets--;
            } else if (brackets == 0 && current == '(') {
                parentheses++;
            } else if (brackets == 0 && current == ')') {
                parentheses--;
            } else if (brackets == 0 && parentheses == 0) {
                structural.append(current);
            }
        }
        String shape = structural.toString().strip();
        if (shape.isEmpty() || shape.contains(",") || brackets != 0 || parentheses != 0 || quote != 0) {
            return false;
        }
        String[] compounds = shape.split("[\\s>+~]+");
        return compounds[compounds.length - 1].matches("(?i)a(?:[.#:].*)?");
    }

    private static String selector(String scenario, String id, String original, Map<String, Target> targets) {
        if (original == null || Set.of("html", "body", ":root").contains(original)) {
            return original;
        }
        var state = STATE.matcher(original);
        String suffix = state.find() ? state.group() : "";
        String base = suffix.isEmpty() ? original : original.substring(0, original.length() - suffix.length());
        String key = base.contains("#") || base.contains("[") ? base : scenario + ":" + base;
        var target = targets.computeIfAbsent(key, value -> new Target("rendra_" + id, base));
        return "[data-testid~=" + quoted(target.testId()) + "]" + suffix;
    }

    private static String internalHref(ApprovedSpec spec, String path) {
        return spec.desired().renderStack() == ApprovedSpec.RenderStack.VITE ? "#" + path : path;
    }

    private static String quoted(String text) {
        if (text.chars().anyMatch(value -> value < 32 || value == 127)) {
            throw new IllegalArgumentException("Acceptance target contains control characters");
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
