package com.code.pal.testsupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.flywaydb.core.Flyway;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real Boot Flyway initialization, including populated Hibernate-created columns and V16 adoption. */
@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
class BootHibernateDriftIT {
    @Configuration(proxyBeanMethods=false)
    @ImportAutoConfiguration({org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class})
    static class BootConfig {}
    @Test void emptyDatabaseMigratesAtBoot() throws Exception {verify(false);}
    @Test void populatedHibernateDatabaseBaselinesAt16AndMigratesExistingColumns() throws Exception {verify(true);}
    private void verify(boolean legacy) throws Exception {
        try(var isolated=CodexIntegrationEnvironment.load(System.getProperty("codex.it.project"),Integer.parseInt(System.getProperty("codex.it.postgres.port")),Integer.parseInt(System.getProperty("codex.it.redis.port")))) {
            var env=isolated.getEnvironment();var ds=new PGSimpleDataSource();ds.setURL(env.getRequiredProperty("spring.datasource.url"));ds.setUser(env.getRequiredProperty("spring.datasource.username"));ds.setPassword(env.getRequiredProperty("spring.datasource.password"));
            try(var conn=ds.getConnection();var st=conn.createStatement();var id=st.executeQuery("select current_database(),current_user")){assertTrue(id.next());assertEquals("codepal_codex_it",id.getString(1));assertEquals("codepal_codex_it",id.getString(2));}
            String schema="codex_boot_"+UUID.randomUUID().toString().replace("-","");ds.setCurrentSchema(schema);var jdbc=new JdbcTemplate(ds);
            if(legacy){Flyway.configure().dataSource(ds).schemas(schema).target("16").load().migrate();
                jdbc.execute("drop table flyway_schema_history");
                jdbc.execute("alter table agent_model_calls add column usage_known boolean");
                for(String column:new String[]{"commit_started_at timestamptz","cancel_requested_at timestamptz","request_message_id varchar(40)","save_recovery_required boolean"})jdbc.execute("alter table agent_runs add column "+column);
                for(String column:new String[]{"build_decision_json text","approved_revision bigint","answer_revision bigint"})jdbc.execute("alter table requirement_sessions add column "+column);
                jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,'legacy',7,'BUILD','FAILED',0,0,now(),now())",UUID.randomUUID(),UUID.randomUUID());
                jdbc.update("insert into requirement_sessions(id,project_id,status,source_prompt,created_at,updated_at) values (?,?,'DRAFT','fixture',now(),now())",UUID.randomUUID(),UUID.randomUUID());
            }
            try(var app=new SpringApplicationBuilder(BootConfig.class).environment(env).web(WebApplicationType.NONE).run("--spring.flyway.enabled=true","--spring.flyway.baseline-on-migrate=true","--spring.flyway.baseline-version=16","--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.datasource.hikari.schema="+schema)){
                var flyway = app.getBean(Flyway.class);
                assertEquals("42", flyway.info().current().getVersion().toString());
                assertEquals(2, jdbc.queryForObject(
                        "select count(*) from information_schema.columns where table_schema=? "
                                + "and table_name='auth_refresh_tokens' "
                                + "and column_name in ('replacement_hash','retry_used_at') and is_nullable='YES'",
                        Integer.class, schema));
                assertEquals(1,jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name='agent_behavior_criteria'",Integer.class,schema));
                if(legacy){assertEquals(1,jdbc.queryForObject("select count(*) from flyway_schema_history where type='BASELINE' and version='16'",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from agent_runs where save_recovery_required is null",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from requirement_sessions where answer_revision is null",Integer.class));}
                assertEquals(0,flyway.migrate().migrationsExecuted);
            }
        }
    }
}
