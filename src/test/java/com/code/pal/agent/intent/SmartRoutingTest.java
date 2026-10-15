package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartRoutingTest {
    @ParameterizedTest
    @ValueSource(strings = {"dashboard looks great", "website is beautiful, thanks", "app is slow", "blog",
            "the build was useful", "my name is Shivam and I enjoy coding", "I need a break", "it needs a rest",
            "build is great", "design looks good", "I want a website explanation", "I need a website review"})
    void evaluationsAndAmbiguousNounsNeverAuthorizeWriting(String phrase) {
        assertEquals(RequestRouter.Route.UNCLEAR, RequestRouter.route(phrase));
        assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.BUILD, phrase, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a todo app", "ek calculator", "landing page for my gym", "website with a contact form",
            "move the logo to the left", "replace the hero image", "header ka color blue karo", "logo bada karo",
            "isme login page daal do", "dark mode chahiye"})
    void clearRequestShapesAuthorizeBuild(String phrase) {
        assertEquals(RequestRouter.Route.CHANGE, RequestRouter.route(phrase));
    }

    @ParameterizedTest
    @ValueSource(strings = {"what can you build?", "why did the build fail?", "how do I deploy?"})
    void actionNounsInQuestionsNeverOfferBuild(String phrase) {
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route(phrase));
        assertFalse(RequestRouter.positiveChange(phrase));
    }

    @ParameterizedTest
    @ValueSource(strings = {"explain and then fix the navbar", "can you tell me why the build failed and fix it",
            "why is the navbar broken? Fix it", "ye kyun hua aur phir theek karo"})
    void onlyImperativeClausesInQuestionsOfferBuild(String phrase) {
        assertTrue(RequestRouter.positiveChange(phrase));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes, build it", "go ahead and build it", "haan bana do please", "ok karo", "haan kar do",
            "yes, build it now", "haan kar do please", "yes, build it, please", "haan bana do, please", "haan,"})
    void politeExplicitAcceptancesResumeOnlyAnExistingOffer(String phrase) {
        assertEquals(RequestRouter.Route.CONTINUE, RequestRouter.route(phrase, List.of(), true));
        assertEquals(RequestRouter.Route.SMALL_TALK, RequestRouter.route(phrase, List.of(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"the page is not loading", "button kaam nahi karta", "kuch dikh nahi raha"})
    void additionalBugShapesRespectTheSelectedAuthority(String phrase) {
        for (ProjectType mode : ProjectType.values()) {
            assertEquals(mode == ProjectType.BUILD ? RequestRouter.Route.CHANGE : RequestRouter.Route.QUESTION,
                    RequestRouter.route(mode, phrase, List.of(), false));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tum kaise kaam karte ho?", "how does rendra work?", "what is rendra?", "who made you?",
            "tum kya kya bana sakte ho?"})
    void productQuestionsAreRecognizedWithoutProjectTools(String phrase) {
        assertTrue(RequestRouter.productQuestion(phrase));
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route(phrase));
    }

    @ParameterizedTest
    @ValueSource(strings = {"I need help", "I want to know who made you", "I need advice on my website"})
    void requestsForHelpOrInformationDoNotStartABuild(String phrase) {
        assertEquals(RequestRouter.Route.QUESTION, RequestRouter.route(phrase));
        assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.BUILD, phrase, List.of()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            no, the header should be blue | CHANGE
            great, but it needs a footer | CHANGE
            hey, i need a todo app for my shop | CHANGE
            test the login form | CHANGE
            thanks for your help, now fix the header | CHANGE
            thanks, the header should be blue | CHANGE
            badal do | CHANGE
            hata do | CHANGE
            lagao | CHANGE
            sudharo | CHANGE
            I need a todo app | CHANGE
            mujhe ek ecommerce website chahiye | CHANGE
            I need a website for my bakery | CHANGE
            mujhe ek todo app chahiye | CHANGE
            landing page for my gym | CHANGE
            I want a website for my bakery | CHANGE
            how are you? | SMALL_TALK
            kaise ho | SMALL_TALK
            what's up | SMALL_TALK
            good job | SMALL_TALK
            my name is Shivam | SMALL_TALK
            bahut badhiya | SMALL_TALK
            Hi | SMALL_TALK
            can u tell how u work? | QUESTION
            ye kaise kaam karta hai? | QUESTION
            how does this app work? | QUESTION
            login page samjhao | QUESTION
            kya tum database bana sakte ho? | QUESTION
            can you tell me why the build failed and fix it | QUESTION
            explain and then fix the navbar | QUESTION
            Don't change anything, explain the backend | QUESTION
            changes nahi krna, sirf check | QUESTION
            """)
    void reviewPhraseTableUsesTheWholeInstruction(String phrase, RequestRouter.Route expected) {
        assertEquals(expected, RequestRouter.route(phrase), phrase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"haan bana do", "ok build karo", "yes please", "haan go ahead", "sure, do it", "do it",
            "haan", "yes", "go ahead"})
    void acknowledgementsWithAPendingOfferAreContinuation(String phrase) {
        assertEquals(RequestRouter.Route.CONTINUE, RequestRouter.route(phrase, List.of(), true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes please", "haan go ahead", "sure, do it", "do it", "haan", "yes", "go ahead"})
    void acknowledgementsWithoutAProposalDoNotAuthorizeChanges(String phrase) {
        assertEquals(RequestRouter.Route.SMALL_TALK, RequestRouter.route(phrase, List.of(), false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            BUILD | page blank hai | BUILD
            BUILD | login button doesn't work | BUILD
            BUILD | kaam nahi kar raha | BUILD
            BUILD | khul nahi raha | BUILD
            BUILD | error aa raha hai | BUILD
            BUILD | page is blank | BUILD
            BUILD | the navbar is broken | BUILD
            CHAT | page blank hai | CHAT
            CHAT | login button doesn't work | CHAT
            CHAT | kaam nahi kar raha | CHAT
            CHAT | khul nahi raha | CHAT
            CHAT | error aa raha hai | CHAT
            PLAN | page blank hai | CHAT
            PLAN | login button doesn't work | CHAT
            PLAN | kaam nahi kar raha | CHAT
            PLAN | khul nahi raha | CHAT
            PLAN | error aa raha hai | CHAT
            """)
    void bugReportsUseBuildAuthorityOnlyInBuildMode(ProjectType mode, String phrase, ProjectType expected) {
        assertEquals(expected, RequestRouter.mode(mode, phrase, List.of()), phrase + " in " + mode);
    }
}
