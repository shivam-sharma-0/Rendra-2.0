package com.code.pal.agent.contract;
import com.code.pal.agent.change.*;
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

class UnitOwnershipTest {
    final LocalProject project=LocalProject.create();final FileChangeRepository audit=mock(FileChangeRepository.class);
    final ChangeSetService changes=new ChangeSetService(audit,mock(AgentRunRepository.class),new ObjectMapper(),project.properties,project.files,project.sandbox,mock(ProjectService.class));
    AgentContext context(){var run=UUID.randomUUID();var ctx=new AgentContext(project.projectId,7L,ProjectType.BUILD,changes.start(run,project.projectId),null,new com.code.pal.agent.context.ProjectContextTrace(),run,new com.code.pal.agent.plan.AgentPlanState(),project.files);ctx.plan().revise(List.of("Implement"),"");return ctx;}
    @Test void createPatchDeleteRenameCopyAndBatchCannotTouchForeignOrSharedPaths() {
        project.write("index.html","old").write("peer.txt","peer");var ctx=context();var c=ContractFixtures.staticSite();
        try(var scope=UnitWriteScope.wave(ctx,c,c.units())){
            assertThrows(RuntimeException.class,()->changes.create(ctx,"foreign.html","bad"));
            assertThrows(RuntimeException.class,()->changes.patch(ctx,"peer.txt",project.files.version(project.projectId,ctx.root(),"peer.txt"),"peer","bad"));
            assertThrows(RuntimeException.class,()->changes.delete(ctx,"peer.txt",project.files.version(project.projectId,ctx.root(),"peer.txt")));
            assertThrows(RuntimeException.class,()->changes.rename(ctx,"index.html","peer2.html",project.files.version(project.projectId,ctx.root(),"index.html")));
            assertThrows(RuntimeException.class,()->changes.copy(ctx,"peer.txt","index.html"));
            assertThrows(RuntimeException.class,()->changes.replaceBatch(ctx,List.of(new ChangeSetService.Replacement(c.clientRoot()+"theme.css","absent","bad"))));
        }
        assertEquals("old",project.files.readText(project.projectId,ctx.root(),"index.html"));verifyNoInteractions(audit);
    }
    @Test void generationScopeCannotMutateEvenItsOwnFilesAndProductionFileBoundaryDeniesRawWrites() {
        var ctx=context();var leases=mock(com.code.pal.agent.lease.ProjectLeaseService.class);var fenced=new FencedProjectFiles(project.files,leases);
        try(var scope=UnitWriteScope.worker(ctx)){
            assertThrows(RuntimeException.class,()->changes.create(ctx,"index.html","bad"));
            assertThrows(RuntimeException.class,()->fenced.writeText(project.projectId,ctx.root(),"index.html","bad"));
            assertThrows(RuntimeException.class,()->fenced.copyFile(project.projectId,".","source",ctx.root(),"index.html"));
        }
        verifyNoInteractions(leases,audit);assertFalse(project.files.exists(project.projectId,ctx.root(),"index.html"));
    }
    @Test void snapshotsAreImmutableAndReturnedForeignVersionsCannotGainAuthority() {
        var c=ContractFixtures.staticSite();var input=new UnitGenerator.Input(c,c.units().getFirst(),"request","requirements",Map.of("index.html",new UnitGenerator.FileView("absent",null)),"");
        assertThrows(UnsupportedOperationException.class,()->input.snapshot().put("peer.txt",new UnitGenerator.FileView("absent","peer")));
        assertThrows(RuntimeException.class,()->UnitGenerator.validate(input,new UnitGenerator.Output(List.of(new UnitGenerator.Delta("peer.txt","absent","peer")))));
    }
    @Test @SuppressWarnings("unchecked") void generationScopeCannotLaunchSandboxCommandsOrTrees() {
        var ctx=context();var leases=mock(com.code.pal.agent.lease.ProjectLeaseService.class);
        var docker=(org.springframework.beans.factory.ObjectProvider<DockerProjectEnvironment>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var local=(org.springframework.beans.factory.ObjectProvider<LocalProjectEnvironment>)mock(org.springframework.beans.factory.ObjectProvider.class);when(local.getObject()).thenReturn(project.sandbox);
        var sandbox=new FencedSandboxPort(docker,local,leases);
        try(var scope=UnitWriteScope.worker(ctx)){
            assertThrows(RuntimeException.class,()->sandbox.exec(project.projectId,ctx.root(),List.of("node","arbitrary.js")));
            assertThrows(RuntimeException.class,()->sandbox.copyTree(project.projectId,ctx.root(),"foreign"));
            assertThrows(RuntimeException.class,()->sandbox.removeTree(project.projectId,ctx.root()));
        }
        verifyNoInteractions(leases);
    }
    @Test void declaredAssetCopyKeepsExactBytesInsideTheOwnersDestination() {
        project.write("source.txt","asset bytes");var ctx=context();var original=ContractFixtures.staticSite();
        var unit=new BuildContract.Unit("page","Implement page",List.of("index.html","logo.txt"),List.of(),List.of(),List.of("result"));
        var c=new BuildContract(original.schemaVersion(),original.id(),original.stack(),original.storage(),original.auth(),original.limits(),original.theme(),original.routes(),original.types(),original.models(),original.api(),List.of(new BuildContract.Asset("source.txt","logo.txt",project.files.version(project.projectId,ctx.root(),"source.txt"),"page")),original.envNames(),List.of(unit),original.acceptance());
        try(var scope=UnitWriteScope.wave(ctx,c,c.units())){changes.copy(ctx,"source.txt","logo.txt");}
        assertEquals(project.files.version(project.projectId,ctx.root(),"source.txt"),project.files.version(project.projectId,ctx.root(),"logo.txt"));assertFalse(project.exists("logo.txt"));
    }
    @Test void copiedAssetMustMatchItsSourceAndUncertainAuditCannotCertifyTheStage() {
        project.write("source.txt","asset bytes");var ctx=context();
        doThrow(new IllegalStateException("unknown audit")).when(audit).save(org.mockito.ArgumentMatchers.any());
        assertThrows(RuntimeException.class,()->changes.copy(ctx,"source.txt","copied.txt"));
        assertThrows(RuntimeException.class,()->changes.validate(ctx));assertFalse(project.exists("copied.txt"));
    }
}
