package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.service.ProjectService;
import org.springframework.stereotype.Service;
import java.util.*;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;

/** Restores only journal-proved code/brief paths. Live data and unrelated edits are untouched. */
@Service
public class CodeRestoreService {
    private final ProjectLeaseService leases; private final PromotionJournal promotions;
    private final RestoreJournal journal; private final ProjectFiles files;
    private final BuildDecisions decisions; private final ProjectService projects;
    private final AgentRunRepository runs;
    public CodeRestoreService(ProjectLeaseService leases, PromotionJournal promotions, RestoreJournal journal,
                              ProjectFiles files, BuildDecisions decisions, ProjectService projects, AgentRunRepository runs) {
        this.leases=leases;this.promotions=promotions;this.journal=journal;this.files=files;this.decisions=decisions;this.projects=projects;
        this.runs=runs;
    }
    public void restore(Long actor, AgentRun run) {
        leases.manualRestore(run.getProjectId(), () -> restoreOwned(actor, run));
    }
    private void restoreOwned(Long actor, AgentRun run) {
        UUID project=run.getProjectId(); projects.requireManageable(actor,project);
        var latest=runs.findTopByProjectIdOrderByCreatedAtDesc(project).orElse(null);
        if(latest==null || !latest.getId().equals(run.getId()) || latest.getCodeSavedAt()==null
                || Set.of(RunStatus.QUEUED,RunStatus.RUNNING,RunStatus.COMMITTING).contains(latest.getStatus()))
            throw conflict("Only the latest saved inactive build can be restored");
        var state=decisions.state(project);
        if(state.blocked()) throw conflict("Build decisions are unavailable for restore compatibility review");
        var choice=state.choice();
        var proof=promotions.read(run.getId());
        if (proof==null || !proof.projectId().equals(project) || !"SAVED".equals(proof.state()))
            throw conflict("This archive has no verified code restore proof");
        if ((choice!=null && (choice.storage()==BuildDecisions.Storage.MERN || choice.storage()==BuildDecisions.Storage.SUPABASE))
                || files.exists(project,".","server/package.json") || files.exists(project,proof.archive(),"server/package.json"))
            throw conflict("Code restore for server-backed apps requires an explicit database schema compatibility review; live data is never rolled back");
        verify(project,".",proof.files(),false); verify(project,proof.archive(),proof.files(),true);
        UUID id=UUID.randomUUID(); String backup=".codepal/restore/"+id;
        journal.prepare(id,run.getId(),project,backup);
        boolean applied=false;
        try {
            for(var f:proof.files()) if(!f.after().equals(FileVersions.ABSENT)) files.copyFile(project,".",f.path(),backup,f.path());
            verify(project,backup,proof.files(),false);
            journal.state(id,"APPLYING"); applied=true;
            copy(project,proof.archive(),proof.files(),true);
            verify(project,".",proof.files(),true);
            journal.state(id,"RESTORED");
        } catch(RuntimeException failed) {
            try {
                if("RESTORED".equals(journal.readState(id))) {verify(project,".",proof.files(),true);return;}
            } catch(RuntimeException unavailable) {throw conflict("Restore outcome requires recovery; project and backups are retained");}
            try {
                if(applied) {verify(project,backup,proof.files(),false);copy(project,backup,proof.files(),false);}
                verify(project,".",proof.files(),false);journal.state(id,"ROLLED_BACK");
            } catch(RuntimeException uncertain) {
                try {journal.state(id,"RECOVERY_REQUIRED");} catch(RuntimeException unavailable) { /* pending evidence remains */ }
                throw conflict("Restore outcome requires recovery; project and backups are retained");
            }
            throw conflict("Restore failed; current touched files were verified and retained");
        }
    }
    private void copy(UUID project,String root,List<PromotionJournal.FileState> proof,boolean before) {
        for(var f:proof) if((before?f.before():f.after()).equals(FileVersions.ABSENT)) files.delete(project,".",f.path());
            else files.copyFile(project,root,f.path(),".",f.path());
    }
    private void verify(UUID project,String root,List<PromotionJournal.FileState> proof,boolean before) {
        for(var f:proof) if(!files.version(project,root,f.path()).equals(before?f.before():f.after()))
            throw conflict("Code or archive changed; restore refused");
    }
    private AgentRunConflictException conflict(String message) {return new AgentRunConflictException(message);}
}
