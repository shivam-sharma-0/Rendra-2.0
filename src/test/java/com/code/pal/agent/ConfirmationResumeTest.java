package com.code.pal.agent;

import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfirmationResumeTest {
    private static final Predicate<Message> FAILED_UNSAVED = earlier -> true;
    private static final Predicate<Message> SAVED_CODE = earlier -> false;
    private final UUID project = UUID.randomUUID();
    private int sequence;

    private Message user(String content, long author) {
        var message = Message.user(project, "u" + ++sequence, content, ProjectType.BUILD, author);
        message.setCreatedAt(Instant.ofEpochSecond(sequence));
        return message;
    }

    private Message reply(Message request, MessageStatus status, ProjectType mode) {
        var message = Message.assistantPlaceholder(project, "a" + ++sequence, request.getId(), mode);
        message.setStatus(status);
        message.setCreatedAt(Instant.ofEpochSecond(sequence));
        return message;
    }

    @Test
    void aGoAheadRightAfterAnInterruptedBuildResumesThatRequest() {
        var todo = user("i want to build a todo application, can u build it?", 7);
        var interrupted = reply(todo, MessageStatus.FAILED, ProjectType.BUILD);
        var goAhead = user("ok make it.", 7);

        assertEquals(todo,
                AgentRunner.unfinishedBuildRequest(List.of(todo, interrupted, goAhead), goAhead, FAILED_UNSAVED));
    }

    @Test
    void anAcceptedOfferResumesByItsResolvedRequestNotItsVagueWords() {
        var accepted = user("hmm", 7);
        accepted.setResolvedRequestText("Build the proposed changes.\n\nOriginal request:\n> hmm");
        var interrupted = reply(accepted, MessageStatus.FAILED, ProjectType.BUILD);
        var goAhead = user("yes", 7);

        assertEquals(accepted,
                AgentRunner.unfinishedBuildRequest(List.of(accepted, interrupted, goAhead), goAhead, FAILED_UNSAVED),
                "the caller then uses its resolved text and attachments");
    }

    @Test
    void aClarifyingQuestionSavedCodeOrAnotherAuthorIsNeverResumed() {
        var todo = user("build a todo app", 7);
        var goAhead = user("yes", 7);

        var question = reply(todo, MessageStatus.COMPLETE, ProjectType.CHAT);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(todo, question, goAhead), goAhead, FAILED_UNSAVED),
                "'yes' to the assistant's question stays chat");

        var failedAfterSave = reply(todo, MessageStatus.FAILED, ProjectType.BUILD);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(todo, failedAfterSave, goAhead), goAhead, SAVED_CODE),
                "a build that saved code is not built again");

        var someoneElse = user("build a todo app", 8);
        var theirFailure = reply(someoneElse, MessageStatus.FAILED, ProjectType.BUILD);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(someoneElse, theirFailure, goAhead), goAhead, FAILED_UNSAVED),
                "another author's request");
    }

    @Test
    void onlyTheImmediatelyPrecedingTurnCounts() {
        var todo = user("build a todo app", 7);
        var failed = reply(todo, MessageStatus.FAILED, ProjectType.BUILD);
        var question = user("how does this work?", 7);
        var answer = reply(question, MessageStatus.COMPLETE, ProjectType.CHAT);
        var goAhead = user("ok make it", 7);

        assertNull(AgentRunner.unfinishedBuildRequest(List.of(todo, failed, question, answer, goAhead), goAhead,
                FAILED_UNSAVED));
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(goAhead), goAhead, FAILED_UNSAVED), "nothing earlier");
    }
}
