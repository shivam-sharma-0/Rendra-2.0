package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestRouterTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Hi | SMALL_TALK
            namaste | SMALL_TALK
            thanks for your help today, I really appreciate all the time you spent helping me | SMALL_TALK
            can u tell how u work? | QUESTION
            Who are you? | QUESTION
            Who created you? | QUESTION
            ye kaise kaam karta hai? | QUESTION
            how does this app work? | QUESTION
            login page samjhao | QUESTION
            kya tum database bana sakte ho? | QUESTION
            Can you build apps? | QUESTION
            Does this support authentication? | QUESTION
            What happens when I add a button? | QUESTION
            What is src/App.tsx? | QUESTION
            add dark mode | CHANGE
            ek calculator banao | CHANGE
            header ka color badlo | CHANGE
            app bana do | CHANGE
            a simple calculator | CHANGE
            a to do app | CHANGE
            please add a button which clears the list | CHANGE
            can you add dark mode? | CHANGE
            A todo app with a clean UI, can u build it? | CHANGE
            i want to buld a end to end with a clean and attractive UI Todo application with all mendatory features, can u build it? | CHANGE
            The footer has no links; could you add them? | CHANGE
            Explain how this works, can you describe the setup? | QUESTION
            Don't change anything, could you explain the build? | QUESTION
            create an app that shows how much I spend | CHANGE
            Don't change anything, explain the backend | QUESTION
            changes nahi krna, sirf check | QUESTION
            just explain how to build an app | QUESTION
            Don't change the theme but add a button | CHANGE
            just explain the error but fix the handler | CHANGE
            sirf plan banao | CHANGE
            haan | SMALL_TALK
            yes | SMALL_TALK
            continue | SMALL_TALK
            ok make it. | SMALL_TALK
            ok make a todo app | CHANGE
            yes, add dark mode | CHANGE
            haan banao | SMALL_TALK
            hmm | UNCLEAR
            what about that? | QUESTION
            src/App.tsx | UNCLEAR
            """)
    void routeTable(String request, RequestRouter.Route expected) {
        assertEquals(expected, RequestRouter.route(request), request);
    }

    @Test
    void quotedReplyAndPastedCodeDoNotAuthorizeChanges() {
        assertEquals(RequestRouter.Route.QUESTION,
                RequestRouter.route("> add dark mode\n> create a database\n\nWhat does this mean?"));
        assertEquals(RequestRouter.Route.UNCLEAR, RequestRouter.route("> build a calculator"));
        assertEquals(RequestRouter.Route.QUESTION,
                RequestRouter.route("```\ncreate a database\n```\nIs this safe?"));
    }

    @Test
    void howTheAppShouldBehaveIsAChangeUnlessItAsksAQuestion() {
        // 2026-10-29 E2E 43: a BUILD follow-up starting with "When" ran as CHAT and changed nothing.
        for (String wish : List.of(
                "When nobody is signed in, the home page should show the title 'My Todos' and a welcome line.",
                "The task list must show the due date next to each title",
                "when a task is done it should be greyed out")) {
            assertEquals(RequestRouter.Route.CHANGE, RequestRouter.route(wish), wish);
            assertEquals(ProjectType.BUILD, RequestRouter.mode(ProjectType.BUILD, wish, List.of()), wish);
        }
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route("When should the home page show the title?"));
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route("Why should I use MongoDB here?"));
    }

    @Test
    void imageRoutingUsesTheActualInstruction() {
        var images = List.of("uploads/mockup.png");
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route("Explain this image", images, false));
        assertEquals(RequestRouter.Route.UNCLEAR, RequestRouter.route("", images, false));
        assertEquals(RequestRouter.Route.UNCLEAR, RequestRouter.route("Hi", images, false));
        assertEquals(RequestRouter.Route.CHANGE, RequestRouter.route("Make a page like this", images, false));
    }

    @Test
    void onlyAnExistingProposalMakesAnAcknowledgementAContinuation() {
        for (String reply : List.of("haan", "yes", "go ahead", "continue", "shuru karo", "aage badho", "ok make it.",
                "make it", "okay, build this", "go for it", "let's do it", "bana do")) {
            assertEquals(RequestRouter.Route.CONTINUE, RequestRouter.route(reply, List.of(), true));
            assertEquals(RequestRouter.Route.SMALL_TALK, RequestRouter.route(reply, List.of(), false));
        }
    }

    @Test
    void explicitModeNeverEscalatesAndQuestionsHaveReadOnlyAuthority() {
        assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.CHAT, "add dark mode", List.of()));
        assertEquals(ProjectType.PLAN, RequestRouter.mode(ProjectType.PLAN, "add dark mode", List.of()));
        assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.BUILD, "how does this app work?", List.of()));
        assertEquals(ProjectType.PLAN, RequestRouter.mode(ProjectType.BUILD, "sirf plan banao", List.of()));
    }
}
