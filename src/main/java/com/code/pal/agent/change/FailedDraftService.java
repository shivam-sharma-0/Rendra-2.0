package com.code.pal.agent.change;

import com.code.pal.agent.domain.*;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.domain.ProjectType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Durable private source; failed artifacts never confer validation or live-code authority. */
@Service
public class FailedDraftService {
    public record Info(String state,boolean available,boolean verified,List<FailedDraftArchive.Source> files,UUID retryRunId) {}
    public record FileView(String path,String content,boolean binary,boolean truncated) {}
    public record Basis(String approvalKey,Map<String,String> liveVersions) {}
    private record BasisEnvelope(UUID runId,UUID projectId,Basis basis) {}
    private final FailedDraftRepository repository;
    private final ProjectFiles files;
    private final ApprovedProjectMemory memory;
    private final ObjectMapper mapper;
    public FailedDraftService(FailedDraftRepository repository,ProjectFiles files,ApprovedProjectMemory memory,ObjectMapper mapper) {
        this.repository=repository;this.files=files;this.memory=memory;this.mapper=mapper;
    }
    public boolean retain(AgentRun run,String stage,List<FailedDraftArchive.Change> changes) {
        return retain(run,stage,changes,null);
    }
    public Basis basis(UUID project,String originalRoot) {return new Basis(approvalKey(project),versions(project,originalRoot));}
    public void persistBasis(AgentRun run,String container,Basis basis) {
        files.writeText(run.getProjectId(),container,"failed-draft-basis.json",mapper.writeValueAsString(new BasisEnvelope(run.getId(),run.getProjectId(),basis)));
    }
    public Basis readBasis(AgentRun run,String container) {
        var value=mapper.readValue(new String(files.readBytes(run.getProjectId(),container,"failed-draft-basis.json",200_000),StandardCharsets.UTF_8),BasisEnvelope.class);
        if(!run.getId().equals(value.runId()) || !run.getProjectId().equals(value.projectId()) || value.basis()==null)
            throw new IllegalStateException("Original failed draft basis unavailable");
        return value.basis();
    }
    public boolean retain(AgentRun run,String stage,List<FailedDraftArchive.Change> changes,Basis basis) {
        if(!eligible(run) || changes.isEmpty()) return false;
        if(repository.find(run.getProjectId(),run.getId())!=null) return true;
        List<FailedDraftArchive.Change> exported=changes.stream().filter(c->FailedDraftArchive.exportable(c.path())).toList();
        if(exported.isEmpty()) return false;
        var source=sourceBytes(run.getProjectId(),stage);
        var sources=source.entrySet().stream().map(e->new FailedDraftArchive.Source(e.getKey(),e.getValue().length,"sha256:"+FailedDraftArchive.hash(e.getValue()))).toList();
        Basis original=basis==null?basis(run.getProjectId(),"."):basis;
        var manifest=new FailedDraftArchive.Manifest(1,run.getId(),run.getProjectId(),original.approvalKey(),original.liveVersions(),sources,exported);
        byte[] archive=FailedDraftArchive.pack(source);
        // Commands and background tasks cannot silently change files between capture and publication.
        for(var proof:sources) if(!files.version(run.getProjectId(),stage,proof.path()).equals(proof.version()))
            throw new IllegalStateException("Draft changed during retention");
        for(var change:exported) if(!files.version(run.getProjectId(),stage,change.path()).equals(change.after()))
            throw new IllegalStateException("Draft mutation proof unavailable");
        for(var change:exported) if(!change.after().equals(FileVersions.ABSENT)
                && (!source.containsKey(change.path()) || !change.after().equals("sha256:"+FailedDraftArchive.hash(source.get(change.path())))))
            throw new IllegalStateException("Draft source capture incomplete");
        repository.retain(new FailedDraftRepository.Stored(run.getId(),run.getProjectId(),mapper.writeValueAsString(manifest),archive,
                FailedDraftArchive.hash(archive),"AVAILABLE",null));
        return true;
    }
    public Info info(UUID project,UUID run) {
        var stored=repository.find(project,run);
        if(stored==null) return new Info("UNAVAILABLE",false,false,List.of(),null);
        var manifest=manifest(stored);
        return new Info(stored.state(),"AVAILABLE".equals(stored.state()),false,manifest.sources(),stored.retryRunId());
    }
    public byte[] download(UUID project,UUID run) {
        var stored=require(project,run);verifiedSources(stored);return stored.archive();
    }
    public FileView file(UUID project,UUID run,String path) {
        if(!FailedDraftArchive.exportable(path)) throw new AgentRunConflictException("This path is not retained source");
        var bytes=verifiedSources(require(project,run)).get(path);
        if(bytes==null) throw new AgentRunConflictException("This file is not in the failed draft");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf('\0')>=0) return new FileView(path,null,true,false);
            return new FileView(path,text.substring(0,Math.min(200_000,text.length())),false,text.length()>200_000);
        }catch(CharacterCodingException binary){return new FileView(path,null,true,false);}
    }
    /** Called both before retry acceptance and under the new worker's lease; stale approvals/code fail closed. */
    public FailedDraftArchive.Manifest retryManifest(UUID project,UUID run) {
        var stored=require(project,run);var manifest=manifest(stored);verifiedSources(stored);
        if(!Objects.equals(manifest.approvalKey(),approvalKey(project)) || !manifest.liveVersions().equals(versions(project,".")))
            throw new AgentRunConflictException("Project code or approved requirements changed. Start a new build instead of retrying this draft.");
        for(var change:manifest.changes()) {
            if(!change.before().equals(manifest.liveVersions().getOrDefault(change.path(),FileVersions.ABSENT)))
                throw new AgentRunConflictException("This failed draft was based on older code. Start a new build.");
        }
        return manifest;
    }
    public Map<String,byte[]> retrySources(UUID project,UUID run){return verifiedSources(require(project,run));}
    public FailedDraftRepository.Stored require(UUID project,UUID run) {
        var stored=repository.find(project,run);
        if(stored==null || !"AVAILABLE".equals(stored.state())) throw new AgentRunConflictException("This failed draft is unavailable or has expired");
        return stored;
    }
    private Map<String,byte[]> verifiedSources(FailedDraftRepository.Stored stored) {
        if(stored.archive()==null || !Objects.equals(stored.hash(),FailedDraftArchive.hash(stored.archive())))
            throw new AgentRunConflictException("Failed draft integrity could not be verified");
        try{return FailedDraftArchive.unpack(stored.archive(),manifest(stored).sources());}
        catch(IllegalStateException invalid){throw new AgentRunConflictException("Failed draft integrity could not be verified");}
    }
    private FailedDraftArchive.Manifest manifest(FailedDraftRepository.Stored stored) {
        try {
            var value=mapper.readValue(stored.manifest(),FailedDraftArchive.Manifest.class);
            if(value.schemaVersion()!=1 || !stored.runId().equals(value.runId()) || !stored.projectId().equals(value.projectId())
                    || value.sources()==null || value.sources().size()>FailedDraftArchive.MAX_FILES || value.liveVersions()==null
                    || value.liveVersions().size()>FailedDraftArchive.MAX_FILES || value.changes()==null || value.changes().size()>FailedDraftArchive.MAX_FILES)
                throw new IllegalStateException();
            var distinct=new HashSet<String>();
            for(var change:value.changes()) if(change==null || !FailedDraftArchive.exportable(change.path()) || !distinct.add(change.path())
                    || !version(change.before()) || !version(change.after())) throw new IllegalStateException();
            return value;
        }catch(RuntimeException invalid){throw new AgentRunConflictException("Failed draft metadata could not be verified");}
    }
    private static boolean version(String v){return v!=null && (v.equals(FileVersions.ABSENT) || v.matches("sha256:[a-f0-9]{64}"));}
    private Map<String,byte[]> sourceBytes(UUID project,String root) {
        Map<String,byte[]> result=new TreeMap<>();long total=0,capturedBytes=0;
        for(var entry:files.list(project,root,64,FailedDraftArchive.SKIP)) {
            if(entry.directory() || !FailedDraftArchive.exportable(entry.path())) continue;
            if(result.size()>=FailedDraftArchive.MAX_FILES || entry.size()>FailedDraftArchive.MAX_FILE_BYTES || (total+=entry.size())>FailedDraftArchive.MAX_BYTES)
                throw new IllegalStateException("Failed source exceeds retention limits");
            byte[] bytes=files.readBytes(project,root,entry.path(),FailedDraftArchive.MAX_FILE_BYTES);
            if((capturedBytes+=bytes.length)>FailedDraftArchive.MAX_BYTES) throw new IllegalStateException("Failed source exceeds retention limits");
            result.put(entry.path(),bytes);
        }
        return result;
    }
    private Map<String,String> versions(UUID project,String root) {
        Map<String,String> versions=new TreeMap<>();
        sourceBytes(project,root).forEach((path,bytes)->versions.put(path,"sha256:"+FailedDraftArchive.hash(bytes)));
        return versions;
    }
    private String approvalKey(UUID project) {
        var approved=memory.current(project);
        return approved==null?"NONE":FailedDraftArchive.hash((approved.projectionId()+":"+approved.revision()+":"+approved.requirementsJson()).getBytes(StandardCharsets.UTF_8));
    }
    public static boolean eligible(AgentRun run) {
        return run.getMode()==ProjectType.BUILD && (run.getStatus()==RunStatus.FAILED || run.getStatus()==RunStatus.CANCELLED)
                && run.getCodeSavedAt()==null && !run.isSaveRecoveryRequired();
    }
}
