package com.code.pal.agent.intent;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RequestScopeTest {
    @Test void explicitInspectionAndPlanReduceBuildAuthority() {
        for(String text:List.of("Don't change anything, explain the backend", "changes nahi krna, sirf check", "just explain how to build an app", "mat banao, sirf samjhao"))
            assertEquals(ProjectType.CHAT,RequestScope.mode(ProjectType.BUILD,text),text);
        for(String text:List.of("Only plan how to build the app","sirf plan banao","pehle plan"))
            assertEquals(ProjectType.PLAN,RequestScope.mode(ProjectType.BUILD,text),text);
        assertEquals(ProjectType.PLAN,RequestScope.mode(ProjectType.PLAN,"implement login"));
        assertEquals(ProjectType.CHAT,RequestScope.mode(ProjectType.CHAT,"build an app"));
    }
    @Test void scopedNegationDoesNotHideTheRequestedEdit() {
        assertEquals(ProjectType.BUILD,RequestScope.mode(ProjectType.BUILD,"Don't change the theme but add a button"));
        assertEquals(ProjectType.BUILD,RequestScope.mode(ProjectType.BUILD,"just explain the error but fix the handler"));
        assertTrue(RequestScope.continuation("haan",ProjectType.BUILD,true));
        assertFalse(RequestScope.continuation("haan",ProjectType.BUILD,false));
        assertFalse(RequestScope.continuation("yes",ProjectType.CHAT,true));
    }
    @Test void clearHinglishAndEnglishRequestsNeedNoIntentModel() {
        var llm=mock(LlmClient.class);var classifier=new IntentClassifier(llm,new AgentProperties());
        for(String text:List.of("calculator banao","header ka color badlo","aage badho","shuru karo","app bana do","a simple calculator"))
            assertEquals(AgentIntent.TASK,classifier.classify(text),text);
        assertEquals(AgentIntent.CONVERSATIONAL,classifier.classify("namaste"));
        verifyNoInteractions(llm);
    }
}
