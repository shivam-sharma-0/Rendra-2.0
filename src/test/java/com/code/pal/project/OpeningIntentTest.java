package com.code.pal.project;

import com.code.pal.agent.preview.PublishedSiteService;
import com.code.pal.identity.service.UserDirectory;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.repository.ProjectMemberRepository;
import com.code.pal.project.repository.ProjectRepository;
import com.code.pal.project.service.impl.ProjectServiceImpl;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.shared.web.SitesOrigin;
import com.code.pal.workspace.service.WorkspaceService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpeningIntentTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            I need a todo app | INITIAL
            mujhe ek ecommerce website chahiye | INITIAL
            I need a website for my bakery | INITIAL
            mujhe ek todo app chahiye | INITIAL
            landing page for my gym | INITIAL
            I need a todo app | PENDING
            mujhe ek ecommerce website chahiye | PENDING
            I need a website for my bakery | PENDING
            mujhe ek todo app chahiye | PENDING
            landing page for my gym | PENDING
            """)
    void newBuildNeedRequestsStartRequirementsWithoutDispatchingAnAgent(String prompt, String entryPoint) {
        var fixture = new Fixture();
        var initial = entryPoint.equals("INITIAL")
                ? new CreateMessageRequest("opening", prompt, ProjectType.BUILD, "opening-answer") : null;
        var request = new CreateProjectRequest("Synthetic opening", ProjectType.BUILD, initial,
                entryPoint.equals("PENDING") ? prompt : null);

        var result = fixture.service.create(7L, UUID.randomUUID(), request);

        assertEquals(ProjectStage.REQUIREMENTS, result.lifecycleStage());
        verifyNoInteractions(fixture.admission, fixture.events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hi", "how are you?", "can u tell how u work?"})
    void ordinaryOpeningChatDoesNotStartRequirements(String prompt) {
        var fixture = new Fixture();
        var result = fixture.service.create(7L, UUID.randomUUID(),
                new CreateProjectRequest("Chat control", ProjectType.BUILD, null, prompt));
        assertEquals(ProjectStage.DESIGN, result.lifecycleStage());
    }

    private static final class Fixture {
        private final AgentTurnAdmission admission = mock(AgentTurnAdmission.class);
        private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        private final ProjectServiceImpl service;

        private Fixture() {
            var projects = mock(ProjectRepository.class);
            when(projects.save(any(Project.class))).thenAnswer(call -> {
                Project project = call.getArgument(0);
                project.setId(UUID.randomUUID());
                return project;
            });
            service = new ProjectServiceImpl(projects, mock(ProjectMemberRepository.class), mock(MessageRepository.class),
                    new MessageSequence(), mock(WorkspaceService.class), mock(UserDirectory.class),
                    mock(NotificationService.class), events, mock(SitesOrigin.class), admission, mock(PublishedSiteService.class));
        }
    }
}
