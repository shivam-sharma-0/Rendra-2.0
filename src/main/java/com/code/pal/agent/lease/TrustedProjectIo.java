package com.code.pal.agent.lease;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Possibly running trusted Docker writes are never forgotten because the CLI stopped responding. */
@Service @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
public class TrustedProjectIo {
    private final JdbcTemplate jdbc;
    public TrustedProjectIo(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public UUID begin(UUID project,String container,String daemon){
        if(daemon==null || !daemon.matches("[A-Za-z0-9:-]{6,100}")) throw new IllegalArgumentException("Invalid Docker daemon identity");
        if(!container.matches("codepal-trusted-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid trusted command identity");
        UUID id=UUID.randomUUID();jdbc.update("insert into agent_project_io(id,project_id,container_name,daemon_id) values (?,?,?,?)",id,project,container,daemon);return id;
    }
    public void finish(UUID id){jdbc.update("update agent_project_io set finished_at=coalesce(finished_at,clock_timestamp()) where id=?",id);}
    public List<Pending> pending(UUID project){return jdbc.query("select id,container_name,daemon_id from agent_project_io where project_id=? and finished_at is null order by created_at limit 100",
            (r,n)->new Pending(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),project);}
    public record Pending(UUID id,String container,String daemon){}
}
