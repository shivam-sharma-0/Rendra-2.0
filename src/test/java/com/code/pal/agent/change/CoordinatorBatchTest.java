package com.code.pal.agent.change;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CoordinatorBatchTest {
    final LocalProject project=LocalProject.create();final ProjectFiles files=spy(project.files);
    final FileChangeRepository audit=mock(FileChangeRepository.class);
    final ChangeSetService changes=new ChangeSetService(audit,mock(AgentRunRepository.class),new ObjectMapper(),project.properties,files,project.sandbox,mock(ProjectService.class));
    AgentContext context(){var run=UUID.randomUUID();var ctx=new AgentContext(project.projectId,7L,ProjectType.BUILD,changes.start(run,project.projectId),null,new com.code.pal.agent.context.ProjectContextTrace(),run,new com.code.pal.agent.plan.AgentPlanState(),files);ctx.plan().revise(List.of("Implement unit"),"");return ctx;}
    ChangeSetService.Replacement replace(AgentContext ctx,String p,String text){return new ChangeSetService.Replacement(p,files.version(project.projectId,ctx.root(),p),text);}
    @Test void mixedReplaceCreateDeleteUsesOneRevisionAndNeverMutatesAcceptedCode() {
        project.write("old.ts","old").write("delete.ts","delete");var ctx=context();
        changes.replaceBatch(ctx,List.of(replace(ctx,"old.ts","new"),replace(ctx,"added.ts","added"),replace(ctx,"delete.ts",null)));
        assertEquals("old",project.read("old.ts"));assertEquals("delete",project.read("delete.ts"));assertFalse(project.exists("added.ts"));
        assertEquals("new",files.readText(project.projectId,ctx.root(),"old.ts"));assertFalse(files.exists(project.projectId,ctx.root(),"delete.ts"));
        assertEquals(1,changes.view(ctx).revision());assertEquals(3,changes.view(ctx).touchedPaths().size());verify(audit).saveAllAndFlush(any());
    }
    @Test void writeThenLostResponseCompensatesEveryEarlierFileWithoutAuditOrRevision() {
        project.write("old.ts","old").write("delete.ts","delete");var ctx=context();
        doAnswer(call->{project.files.writeText(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));throw new IllegalStateException("lost response");}).when(files).writeText(eq(project.projectId),eq(ctx.root()),eq("added.ts"),eq("added"));
        assertThrows(RuntimeException.class,()->changes.replaceBatch(ctx,List.of(replace(ctx,"old.ts","new"),replace(ctx,"delete.ts",null),replace(ctx,"added.ts","added"))));
        assertEquals("old",files.readText(project.projectId,ctx.root(),"old.ts"));assertEquals("delete",files.readText(project.projectId,ctx.root(),"delete.ts"));assertFalse(files.exists(project.projectId,ctx.root(),"added.ts"));assertEquals(0,changes.view(ctx).revision());verifyNoInteractions(audit);
    }
    @Test void staleLastTargetRefusesTheEntireBatchBeforeAnyWrite() {
        project.write("old.ts","old");var ctx=context();var original=replace(ctx,"old.ts","new");
        assertThrows(RuntimeException.class,()->changes.replaceBatch(ctx,List.of(original,new ChangeSetService.Replacement("added.ts","wrong","added"))));
        assertEquals("old",files.readText(project.projectId,ctx.root(),"old.ts"));verifyNoInteractions(audit);
    }
    @Test void uncertainAuditBlocksValidationAndSaveRatherThanClaimingRollback() {
        var ctx=context();doThrow(new IllegalStateException("commit response unknown")).when(audit).saveAllAndFlush(any());
        assertThrows(RuntimeException.class,()->changes.replaceBatch(ctx,List.of(replace(ctx,"added.ts","added"))));
        assertThrows(RuntimeException.class,()->changes.validate(ctx));assertFalse(project.exists("added.ts"));
    }
}
