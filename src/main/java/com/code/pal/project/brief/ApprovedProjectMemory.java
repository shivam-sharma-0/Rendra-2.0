package com.code.pal.project.brief;

import com.code.pal.project.requirement.RequirementProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import com.code.pal.project.requirement.RequirementDraft;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;

/** Current immutable approved projection; conversation history and editable files are never authority. */
@Service
public class ApprovedProjectMemory {
    public record Approved(UUID projectionId, long revision, String requirementsJson, String brief, String specJson) {
        public Approved(UUID projectionId, long revision, String requirementsJson, String brief) {
            this(projectionId, revision, requirementsJson, brief, null);
        }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String BEGIN = "<!-- rendra-code-snapshot:start -->";
    private static final String END = "<!-- rendra-code-snapshot:end -->";
    public ApprovedProjectMemory(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public Approved current(UUID project) {
        var rows=jdbc.query("select p.id,p.revision,p.payload_json from requirement_sessions s join requirement_projections p on p.id=s.projection_id "
                +"where s.project_id=? and p.project_id=s.project_id and p.session_id=s.id and s.status='APPROVED' and s.approved_revision=s.answer_revision and p.revision=s.approved_revision and p.state='READY'",
                (r,n)->{
                    var payload=mapper.readValue(r.getString(3),RequirementProjection.Payload.class);
                    if(payload.requirementsJson()==null || payload.brief()==null) throw new IllegalStateException("Invalid approved memory");
                    return new Approved(r.getObject(1,UUID.class),r.getLong(2),payload.requirementsJson(),payload.brief(),payload.specJson());
                },project);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public String prompt(UUID project) {
        Approved approved=current(project);
        if(approved==null) return "";
        var source=mapper.readTree(approved.requirementsJson());
        if(!source.path("answers").isArray() || source.path("answers").isEmpty()) throw new IllegalStateException("Invalid approved answers");
        List<Map<String,Object>> answers=new ArrayList<>();
        int index=0;
        for(var answer:source.path("answers")) {
            if(index>=8) throw new IllegalStateException("Approved question count exceeds context limit");
            String question=answer.path("question").asString("");
            String value=answer.path("answer").asString("");
            answers.add(Map.of("index",index++,"question",clip(question,160),"answer",clip(value,400),
                    "partial",question.length()>160 || value.length()>400));
        }
        var values=new LinkedHashMap<String,Object>();
        values.put("schemaVersion",1);values.put("projectionId",approved.projectionId());
        values.put("revision",approved.revision());values.put("answers",answers);
        var scope=RequirementDraft.review(approved.specJson(),false);
        if(scope!=null) values.put("requestedScope",scope);
        String json=mapper.writeValueAsString(values);
        // JSON values remain user data and cannot close the surrounding metadata block.
        json=json.replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026");
        return PromptLoader.load(Prompt.APPROVED_MEMORY).replace("{{data}}",json)+"\n";
    }
    public Map<String,Object> answer(UUID project, int index, int offset, int chars, Long expectedRevision) {
        if(index<0 || index>=8 || offset<0 || chars<1 || chars>4000) throw new IllegalArgumentException("Invalid approved-answer range");
        var approved=current(project);
        if(approved==null) throw new IllegalArgumentException("No current approved requirements");
        if(offset>0 && !Objects.equals(expectedRevision,approved.revision())) throw new IllegalArgumentException("Approved revision changed or is missing; restart at offset zero");
        var answers=mapper.readTree(approved.requirementsJson()).path("answers");
        if(!answers.isArray()) throw new IllegalStateException("Invalid approved answers");
        if(index>=answers.size()) throw new IllegalArgumentException("Approved requirement not found");
        String full=mapper.writeValueAsString(answers.get(index));
        if(offset>full.length()) throw new IllegalArgumentException("Offset outside approved requirement");
        int end=(int)Math.min(full.length(),(long)offset+chars);
        return Map.of("schemaVersion",1,"projectionId",approved.projectionId(),"revision",approved.revision(),"index",index,
                "offset",offset,"nextOffset",end,"totalChars",full.length(),"partial",offset>0 || end<full.length(),"hasMore",end<full.length(),"content",full.substring(offset,end));
    }
    /** Managed blocks are replaced, everything outside them remains user notes. */
    public String synchronize(UUID project, UUID run, String existing, Map<String,String> changedFiles) {
        var approved=current(project);
        String result=approved==null?existing:RequirementProjection.renderBrief(existing,
                approved.brief().replace(BEGIN,"[snapshot marker]").replace(END,"[snapshot marker]"),approved.revision());
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("schemaVersion",1);state.put("runId",run);state.put("coverage","changed-files-only");state.put("fileVersions",new TreeMap<>(changedFiles));
        state.put("approvedTargetProjectionId",approved==null?null:approved.projectionId());
        state.put("approvedTargetRevision",approved==null?null:approved.revision());
        String content=mapper.writeValueAsString(state).replace("<","\\u003c").replace(">","\\u003e");
        String block=BEGIN+"\n## Code snapshot descriptor\n\n```json\n"+content+"\n```\n"+END;
        int begin=result.indexOf(BEGIN),end=result.indexOf(END);
        if(begin<0 && end<0) result=result.stripTrailing()+"\n\n"+block+"\n";
        else {
            if(begin<0 || end<begin || result.indexOf(BEGIN,begin+BEGIN.length())>=0 || result.indexOf(END,end+END.length())>=0)
                throw new IllegalStateException("Ambiguous code snapshot markers");
            result=result.substring(0,begin)+block+result.substring(end+END.length());
        }
        if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128_000) throw new IllegalStateException("Synchronized brief exceeds limit");
        return result;
    }
    private static String clip(String value,int max) { return value.length()<=max?value:value.substring(0,max); }
}
