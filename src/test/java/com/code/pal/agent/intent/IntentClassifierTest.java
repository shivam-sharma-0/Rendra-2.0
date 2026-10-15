package com.code.pal.agent.intent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntentClassifierTest {
    @Test void ambiguousRepliesNeverSpendAClassificationCallOrAuthorizeMutation() {
        var model=mock(LlmClient.class);var classifier=new IntentClassifier(model,new AgentProperties());
        for(String text:List.of("I'm good, you?","what about that?","hmm","nice","hi"))assertEquals(AgentIntent.CONVERSATIONAL,classifier.classify(text));
        verifyNoInteractions(model);
    }
    @Test void concreteTasksPathsAndHinglishActionsStillRouteWithoutAClassificationCall() {
        var model=mock(LlmClient.class);var classifier=new IntentClassifier(model,new AgentProperties());
        for(String text:List.of("a to do app","ek app banao","fix src/App.tsx","Build a calculator"))assertEquals(AgentIntent.TASK,classifier.classify(text),text);
        verifyNoInteractions(model);
    }
}
