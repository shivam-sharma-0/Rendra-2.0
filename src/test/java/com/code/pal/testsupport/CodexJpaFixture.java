package com.code.pal.testsupport;

import com.code.pal.agent.dispatch.AgentDispatch;
import com.code.pal.agent.dispatch.AgentDispatchRepository;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.usage.ModelCall;
import com.code.pal.agent.usage.ModelCallRepository;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

/** Selected real persistence beans only: no application scan, workers, scheduling or provider config. */
public final class CodexJpaFixture {
    public static AnnotationConfigApplicationContext open(Class<?> testConfiguration) {
        try (var isolated = CodexIntegrationEnvironment.load(System.getProperty("codex.it.project"),
                Integer.parseInt(System.getProperty("codex.it.postgres.port", "0")),
                Integer.parseInt(System.getProperty("codex.it.redis.port", "0")))) {
            var context = new AnnotationConfigApplicationContext();
            context.setEnvironment(isolated.getEnvironment());
            context.register(PersistenceConfiguration.class, testConfiguration);
            try { context.refresh(); return context; }
            catch (RuntimeException failure) { context.close(); throw failure; }
        }
    }

    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    public static class PersistenceConfiguration {
        @Bean DataSource dataSource(org.springframework.core.env.Environment env) throws Exception {
            var source = new PGSimpleDataSource();
            source.setURL(env.getRequiredProperty("spring.datasource.url"));
            source.setUser(env.getRequiredProperty("spring.datasource.username"));
            source.setPassword(env.getRequiredProperty("spring.datasource.password"));
            source.setConnectTimeout(5);
            source.setSocketTimeout(15);
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                 var identity = statement.executeQuery("select current_database(), current_user")) {
                if (!identity.next() || !"codepal_codex_it".equals(identity.getString(1))
                        || !"codepal_codex_it".equals(identity.getString(2)))
                    throw new IllegalStateException("Refusing non-disposable persistence target");
            }
            String schema = "codex_it_" + UUID.randomUUID().toString().replace("-", "");
            source.setCurrentSchema(schema);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("16").load().migrate();
            // Real pre-migration rows: legacy duplicates must remain compatible and retain their usage.
            var jdbc = new JdbcTemplate(source);
            UUID legacyProject = UUID.randomUUID();
            UUID firstLegacy = UUID.randomUUID();
            for (int i = 0; i < 2; i++) jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) "
                    + "values (?,?,'legacy_duplicate',7,'CHAT','DONE',12,3,now(),now())", i == 0 ? firstLegacy : UUID.randomUUID(), legacyProject);
            jdbc.update("insert into agent_model_calls(id,user_id,project_id,run_id,purpose,prompt_tokens,completion_tokens,outcome,counted_in_run) "
                    + "values (?,7,?,?,'AGENT',12,3,'OK',true)", UUID.randomUUID(), legacyProject, firstLegacy);
            jdbc.update("insert into agent_model_calls(id,user_id,purpose,prompt_tokens,completion_tokens,outcome,counted_in_run) "
                    + "values (?,7,'REQUIREMENTS',4,2,'OK',false)", UUID.randomUUID());
            jdbc.update("insert into agent_model_calls(id,user_id,purpose,estimated_input_tokens,max_tokens,outcome,counted_in_run) "
                    + "values (?,7,'VISION',10,20,'FAILED',false)", UUID.randomUUID());
            UUID midnightRun = UUID.randomUUID();
            jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) "
                    + "values (?,?,'legacy_midnight',9,'CHAT','DONE',12,3,'2026-10-09T23:59:59Z','2026-10-10T00:00:01Z')", midnightRun, legacyProject);
            jdbc.update("insert into agent_model_calls(id,user_id,project_id,run_id,purpose,prompt_tokens,completion_tokens,outcome,counted_in_run,created_at) "
                    + "values (?,9,?,?,'AGENT',12,3,'OK',true,'2026-10-10T00:00:01Z')", UUID.randomUUID(), legacyProject, midnightRun);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            return source;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setManagedTypes(PersistenceManagedTypes.of(Message.class.getName(), AgentRun.class.getName(), AgentDispatch.class.getName(), ModelCall.class.getName(),
                    com.code.pal.project.domain.RequirementSession.class.getName(), com.code.pal.project.domain.RequirementQuestion.class.getName(),
                    com.code.pal.project.supabase.SupabaseConnection.class.getName(),
                    com.code.pal.agent.change.FileChange.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean JpaRepositoryFactoryBean<MessageRepository, Message, String> messages(EntityManagerFactory factory) {
            return repository(MessageRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<AgentRunRepository, AgentRun, UUID> runs(EntityManagerFactory factory) {
            return repository(AgentRunRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<AgentDispatchRepository, AgentDispatch, UUID> outbox(EntityManagerFactory factory) {
            return repository(AgentDispatchRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<ModelCallRepository, ModelCall, UUID> modelCalls(EntityManagerFactory factory) {
            return repository(ModelCallRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<com.code.pal.project.repository.RequirementSessionRepository, com.code.pal.project.domain.RequirementSession, UUID> requirementSessions(EntityManagerFactory factory) {
            return repository(com.code.pal.project.repository.RequirementSessionRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<com.code.pal.project.repository.RequirementQuestionRepository, com.code.pal.project.domain.RequirementQuestion, UUID> requirementQuestions(EntityManagerFactory factory) {
            return repository(com.code.pal.project.repository.RequirementQuestionRepository.class, factory);
        }
        @Bean JpaRepositoryFactoryBean<com.code.pal.project.supabase.SupabaseConnectionRepository, com.code.pal.project.supabase.SupabaseConnection, UUID> supabaseConnections(EntityManagerFactory factory) {
            return repository(com.code.pal.project.supabase.SupabaseConnectionRepository.class, factory);
        }

        private static <R extends org.springframework.data.repository.Repository<T, I>, T, I>
                JpaRepositoryFactoryBean<R, T, I> repository(Class<R> type, EntityManagerFactory factory) {
            var bean = new JpaRepositoryFactoryBean<R, T, I>(type);
            bean.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(factory));
            bean.setTransactionManager("transactionManager");
            return bean;
        }
    }
}
