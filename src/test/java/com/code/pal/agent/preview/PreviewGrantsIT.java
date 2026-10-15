package com.code.pal.agent.preview;
import com.code.pal.identity.security.*;
import com.code.pal.identity.domain.User;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewGrantsIT {
    AnnotationConfigApplicationContext app;JdbcTemplate jdbc;JwtService jwt;AuthSessions sessions;PreviewGrants grants;ProjectService rights;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);jwt=app.getBean(JwtService.class);sessions=app.getBean(AuthSessions.class);grants=app.getBean(PreviewGrants.class);rights=app.getBean(ProjectService.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    class Fixture {
        final UUID project=UUID.randomUUID();final User user;final com.code.pal.identity.dto.AuthResponse tokens;final AuthPrincipal actor;
        Fixture(){UUID publicId=UUID.randomUUID();String email=publicId+"@example.invalid";Long id=jdbc.queryForObject("insert into users(public_id,email,password_hash,provider,role,email_verified,enabled,created_at,updated_at) values (?,?,'fixture','LOCAL','USER',true,true,now(),now()) returning id",Long.class,publicId,email);
            user=User.builder().id(id).publicId(publicId).email(email).build();tokens=sessions.issue(user);actor=jwt.toPrincipal(jwt.parse(tokens.accessToken()));
            jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,?,'Grant fixture','BUILD',false,'NONE',now(),now(),now())",project,UUID.randomUUID(),id);}
    }
    @Test void restartExpiryLogoutAndCurrentMembershipControlEveryRead() {
        var f=new Fixture();var grant=grants.issue(f.actor,f.project,null);assertEquals(grant,grants.issue(f.actor,f.project,null));assertEquals(f.project,new PreviewGrants(jdbc,sessions,rights,1800000).resolve(grant.key()).projectId());
        doThrow(new IllegalStateException("Access revoked")).when(rights).requireAccessible(f.actor.userId(),f.project);assertThrows(RuntimeException.class,()->grants.resolve(grant.id()));reset(rights);
        sessions.logout(f.tokens.refreshToken());assertThrows(RuntimeException.class,()->grants.resolve(grant.id()));
        var other=new Fixture();var expired=grants.issue(other.actor,other.project,null);jdbc.update("update preview_grants set expires_at=clock_timestamp()-interval '1 second' where id=?",expired.id());assertThrows(RuntimeException.class,()->grants.resolve(expired.id()));
        assertThrows(RuntimeException.class,()->grants.resolve(UUID.randomUUID()));assertThrows(RuntimeException.class,()->grants.resolve("old-stable-project-key"));
    }
    @Test void draftBindingAndExplicitRevocationDoNotGrantOtherActorsOrProjectsAccess() {
        var f=new Fixture();UUID run=UUID.randomUUID();jdbc.update("insert into agent_runs(id,project_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,?,'BUILD','RUNNING',0,0,now(),now())",run,f.project,f.actor.userId());
        var draft=grants.issue(f.actor,f.project,run);assertEquals(run,grants.resolve(draft.key()).draftRunId());
        var other=new Fixture();assertThrows(RuntimeException.class,()->grants.issue(other.actor,other.project,run));
        var normal=grants.issue(f.actor,f.project,null);var second=grants.issue(other.actor,f.project,null);
        grants.revoke(f.actor,f.project);assertThrows(RuntimeException.class,()->grants.resolve(normal.id()));assertThrows(RuntimeException.class,()->grants.resolve(draft.id()));assertEquals(other.actor.userId(),grants.resolve(second.id()).userId());
        assertThrows(RuntimeException.class,()->grants.issue(new AuthPrincipal(f.actor.userId(),f.actor.publicId(),f.actor.email(),f.actor.role()),f.project,null));
    }
    @Configuration @Import({AuthSessions.class,PreviewGrants.class}) static class Services {
        @Bean ProjectService projects(){return mock(ProjectService.class);}
        @Bean JwtService jwt() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();return new JwtService((RSAPrivateKey)key.getPrivate(),(RSAPublicKey)key.getPublic(),900000,604800000,1800000);}
    }
}
