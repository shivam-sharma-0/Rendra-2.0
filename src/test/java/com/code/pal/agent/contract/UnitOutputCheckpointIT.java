package com.code.pal.agent.contract;

import com.code.pal.agent.change.SequentialCheckpoints;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.route.*;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnitOutputCheckpointIT {
    AnnotationConfigApplicationContext app;JdbcTemplate jdbc;UnitOutputCheckpoints outputs;ProjectLeaseService leases;SequentialCheckpoints progress;ContractStoreIT helper;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);outputs=app.getBean(UnitOutputCheckpoints.class);leases=app.getBean(ProjectLeaseService.class);progress=app.getBean(SequentialCheckpoints.class);
        helper=new ContractStoreIT();helper.app=app;helper.jdbc=jdbc;helper.store=app.getBean(ContractStore.class);helper.mapper=app.getBean(ObjectMapper.class);helper.files=app.getBean(ProjectFiles.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    class Fixture {
        final ContractStoreIT.Fixture base=helper.new Fixture();final AgentRun run=base.run(ProjectType.BUILD);final RunFence fence;
        final BuildContract c=base.contract;final BuildContract.Unit unit=c.units().getFirst();final AgentContext ctx;
        Fixture(){base.done();run.setRequestFingerprint("a".repeat(64));jdbc.update("update agent_runs set request_fingerprint=? where id=?",run.getRequestFingerprint(),run.getId());fence=leases.claim(base.project,run.getId(),UUID.randomUUID());assertNotNull(fence);
            ctx=new AgentContext(base.project,7L,ProjectType.BUILD,".",null,new com.code.pal.agent.context.ProjectContextTrace(),run.getId(),new com.code.pal.agent.plan.AgentPlanState(),helper.files);
            try(var scope=RunFence.bind(fence)){helper.store.create(run,c,base.basis,c.limits());progress.configure(run,new RunSize(SizeTier.S,List.of(),300000,1,1,40));}}
        UnitGenerator.Input input(String version){return new UnitGenerator.Input(c,unit,"build","Approved",Map.of("index.html",new UnitGenerator.FileView(version,"before")),"");}
        UnitGenerator.Input input(){return input(helper.files.version(base.project,".","index.html"));}
        UnitGenerator.Output proposal(String version,String text){return new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html",version,text)));}
        void save(){try(var scope=RunFence.bind(fence)){assertTrue(progress.reserveModelIteration(run));outputs.save(run,input(),proposal(input().snapshot().get("index.html").version(),"<main id='result'>5</main>"));}}
        boolean requeue(){jdbc.update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?",base.project);var result=new java.util.concurrent.atomic.AtomicBoolean();leases.recovering(base.project,()->result.set(progress.requeue(run)));return result.get();}
    }
    @Test void restartReusesProposalAtModelCapWithoutResettingBudgetOrCertifyingIt(){
        var f=new Fixture();f.save();var deadline=jdbc.queryForObject("select deadline_at from agent_execution_progress where run_id=?",java.sql.Timestamp.class,f.run.getId());
        var restarted=new UnitOutputCheckpoints(jdbc,app.getBean(PlatformTransactionManager.class),helper.mapper,helper.store,leases,helper.files,app.getBean(AgentProperties.class));
        assertEquals("<main id='result'>5</main>",restarted.reuse(f.run,f.input(),f.ctx).files().getFirst().content());assertEquals("GENERATED",jdbc.queryForObject("select state from agent_unit_outputs where run_id=?",String.class,f.run.getId()));
        assertTrue(f.requeue());assertEquals("QUEUED",jdbc.queryForObject("select status from agent_runs where id=?",String.class,f.run.getId()));assertEquals(1,progress.progress(f.run.getId()).modelCalls());assertEquals(1,progress.progress(f.run.getId()).resumeCount());
        assertEquals(deadline,jdbc.queryForObject("select deadline_at from agent_execution_progress where run_id=?",java.sql.Timestamp.class,f.run.getId()));assertEquals(1,jdbc.queryForObject("select count(*) from agent_dispatch_outbox where run_id=? and dispatched_at is null",Integer.class,f.run.getId()));
        jdbc.update("update agent_runs set status='RUNNING' where id=?",f.run.getId());assertFalse(f.requeue());assertEquals("before",helper.files.readText(f.base.project,".","index.html"));
    }
    @Test void changedContextRequestAndForeignRunCannotReuse(){
        var f=new Fixture();f.save();var snapshot=new HashMap<>(f.input().snapshot());snapshot.put("context.ts",new UnitGenerator.FileView("sha256:"+"b".repeat(64),"changed"));
        assertNull(outputs.reuse(f.run,new UnitGenerator.Input(f.c,f.unit,"build","Approved",snapshot,""),f.ctx));
        f.run.setRequestFingerprint("c".repeat(64));assertNull(outputs.reuse(f.run,f.input(),f.ctx));f.run.setRequestFingerprint("a".repeat(64));
        assertThrows(IllegalStateException.class,()->outputs.reuse(f.run,f.input(),new AgentContext(f.base.project,7L,ProjectType.BUILD,".",helper.files)));
        helper.files.writeText(f.base.project,".","index.html","user edit");assertFalse(outputs.canResume(f.run));assertThrows(IllegalStateException.class,()->outputs.reuse(f.run,f.input(),f.ctx));
    }
    @Test void proposalGeneratedWithPartnerContextCannotReplayAgainstDifferentPartnerInput(){
        var f=new Fixture();var snapshot=new HashMap<>(f.input().snapshot());
        snapshot.put("style.css",new UnitGenerator.FileView("absent",null));
        var frozen=new UnitGenerator.Input(f.c,f.unit,"build","Approved",snapshot,"");
        try(var scope=RunFence.bind(f.fence)){outputs.save(f.run,frozen,f.proposal(f.input().snapshot().get("index.html").version(),"<main id='result'>5</main>"));}
        assertNotNull(outputs.reuse(f.run,frozen,f.ctx));assertNull(outputs.reuse(f.run,f.input(),f.ctx));
        snapshot.put("style.css",new UnitGenerator.FileView("sha256:"+ContractStore.hash("changed style"),"changed style"));
        assertNull(outputs.reuse(f.run,new UnitGenerator.Input(f.c,f.unit,"build","Approved",snapshot,""),f.ctx));
        assertEquals("before",helper.files.readText(f.base.project,".","index.html"));
    }
    @Test void corruptedProposalFailsClosedAndStopPreventsRecovery(){
        var f=new Fixture();f.save();jdbc.update("update agent_unit_outputs set output_json=output_json || ' ' where run_id=?",f.run.getId());
        assertThrows(IllegalStateException.class,()->outputs.reuse(f.run,f.input(),f.ctx));assertThrows(IllegalStateException.class,()->outputs.canResume(f.run));
        var stopped=new Fixture();stopped.save();jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?",stopped.run.getId());assertFalse(stopped.requeue());
        assertEquals(0,progress.progress(stopped.run.getId()).resumeCount());
    }
    @Test void latestRepairReplaysOntoOriginalBaselineAndVerifiedStateStillNeedsFreshChecks(){
        var f=new Fixture();f.save();String first="<main id='result'>5</main>",last="<main id='result'>6</main>";
        try(var scope=RunFence.bind(f.fence)){outputs.save(f.run,f.input("sha256:"+ContractStore.hash(first)),f.proposal("sha256:"+ContractStore.hash(first),last));}
        var replay=outputs.reuse(f.run,f.input(),f.ctx);assertEquals(last,replay.files().getFirst().content());assertEquals(f.input().snapshot().get("index.html").version(),replay.files().getFirst().expectedVersion());
        try(var scope=RunFence.bind(f.fence)){outputs.verified(f.run);}assertEquals("VERIFIED",jdbc.queryForObject("select state from agent_unit_outputs where run_id=? order by created_at desc,id desc limit 1",String.class,f.run.getId()));
        assertEquals(last,outputs.reuse(f.run,f.input(),f.ctx).files().getFirst().content());assertEquals("before",helper.files.readText(f.base.project,".","index.html"));
    }
    @Configuration @Import({ContractStoreIT.Services.class,UnitOutputCheckpoints.class,SequentialCheckpoints.class}) static class Services {}
}
