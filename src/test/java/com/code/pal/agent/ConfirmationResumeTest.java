package com.code.pal.agent;

import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfirmationResumeTest {
    private final UUID project = UUID.randomUUID();
    private int sequence;

    private Message user(String content, long author) {
        var message = Message.user(project, "u" + ++sequence, content, ProjectType.BUILD, author);
        message.setCreatedAt(Instant.ofEpochSecond(sequence));
        return message;
    }

    private Message reply(Message request, MessageStatus status) {
        var message = Message.assistantPlaceholder(project, "a" + ++sequence, request.getId(), ProjectType.BUILD);
        message.setStatus(status);
        message.setCreatedAt(Instant.ofEpochSecond(sequence));
        return message;
    }

    @Test
    void aGoAheadAfterAnInterruptedBuildRequestResumesThatRequest() {
        var todo = user("i want to build a todo application, can u build it?", 7);
        var interrupted = reply(todo, MessageStatus.FAILED);
        var goAhead = user("ok make it.", 7);

        assertEquals(todo.getContent(), AgentRunner.unfinishedBuildRequest(List.of(todo, interrupted, goAhead), goAhead));
    }

    @Test
    void aFinishedBuildAnotherAuthorOrANonChangeTurnIsNeverResumed() {
        var todo = user("build a todo app", 7);
        var goAhead = user("ok make it", 7);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(todo, reply(todo, MessageStatus.COMPLETE), goAhead), goAhead),
                "already built");

        var someoneElse = user("build a todo app", 8);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(someoneElse, goAhead), goAhead), "another author's request");

        var question = user("how does this work?", 7);
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(todo, question, goAhead), goAhead),
                "only the immediately previous request counts");
        assertNull(AgentRunner.unfinishedBuildRequest(List.of(goAhead), goAhead), "nothing earlier");
    }
}
