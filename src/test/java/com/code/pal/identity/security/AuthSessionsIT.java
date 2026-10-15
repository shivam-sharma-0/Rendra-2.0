package com.code.pal.identity.security;
import com.code.pal.identity.domain.User;
import com.code.pal.identity.exception.InvalidCredentialsException;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSessionsIT {
    AnnotationConfigApplicationContext app;JdbcTemplate jdbc;JwtService jwt;AuthSessions sessions;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);jwt=app.getBean(JwtService.class);sessions=app.getBean(AuthSessions.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    User user(){UUID id=UUID.randomUUID();String email=id+"@example.invalid";
        Long user=jdbc.queryForObject("insert into users(public_id,email,password_hash,name,provider,role,email_verified,enabled,created_at,updated_at) values (?,?,'fixture','Fixture','LOCAL','USER',true,true,now(),now()) returning id",Long.class,id,email);
        return User.builder().id(user).publicId(id).email(email).build();}
    @Test void rotationUsesHashOnlyStorageAndReusedRefreshRevokesTheWholeFamily() {
        var user=user();var initial=sessions.issue(user);assertTrue(sessions.accessAllowed(jwt.parse(initial.accessToken())));
        assertFalse(initial.refreshToken().equals(jdbc.queryForObject("select token_hash from auth_refresh_tokens where session_id=?",String.class,jwt.sessionId(jwt.parse(initial.refreshToken())))));
        var rotated=sessions.rotate(user,jwt.parse(initial.refreshToken()),initial.refreshToken());assertNotEquals(initial.refreshToken(),rotated.refreshToken());assertTrue(sessions.accessAllowed(jwt.parse(rotated.accessToken())));
        jdbc.update("update auth_refresh_tokens set used_at=clock_timestamp()-interval '21 seconds' where token_hash in (select token_hash from auth_refresh_tokens where session_id=? and used_at is not null)",
                jwt.sessionId(jwt.parse(initial.refreshToken())));
        assertThrows(com.code.pal.identity.exception.InvalidCredentialsException.class,()->sessions.rotate(user,jwt.parse(initial.refreshToken()),initial.refreshToken()));
        assertFalse(sessions.accessAllowed(jwt.parse(initial.accessToken())));assertFalse(sessions.accessAllowed(jwt.parse(rotated.accessToken())));
        assertThrows(com.code.pal.identity.exception.InvalidCredentialsException.class,()->sessions.rotate(user,jwt.parse(rotated.refreshToken()),rotated.refreshToken()));
    }
    @Test void logoutDisabledUserExpiredFamilyAndLegacyTokensCannotAuthorize() {
        var user=user();var issued=sessions.issue(user);sessions.logout(issued.refreshToken());sessions.logout(issued.refreshToken());assertFalse(sessions.accessAllowed(jwt.parse(issued.accessToken())));
        var active=sessions.issue(user);jdbc.update("update users set enabled=false where id=?",user.getId());assertFalse(sessions.accessAllowed(jwt.parse(active.accessToken())));
        jdbc.update("update users set enabled=true where id=?",user.getId());jdbc.update("update auth_sessions set expires_at=clock_timestamp()-interval '1 second' where id=?",jwt.sessionId(jwt.parse(active.accessToken())));
        assertFalse(sessions.accessAllowed(jwt.parse(active.accessToken())));assertThrows(com.code.pal.identity.exception.InvalidCredentialsException.class,()->sessions.rotate(user,jwt.parse(active.refreshToken()),active.refreshToken()));
        assertFalse(sessions.accessAllowed(jwt.parse(jwt.generateAccessToken(user))));
    }
    @Test void simultaneousUseAllowsOneGraceRetryAndKeepsOneCurrentRefreshToken() throws Exception {
        var user=user();var issued=sessions.issue(user);var start=new CountDownLatch(1);var successes=new java.util.concurrent.atomic.AtomicInteger();var rejected=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{try{start.await();sessions.rotate(user,jwt.parse(issued.refreshToken()),issued.refreshToken());successes.incrementAndGet();}
                catch(com.code.pal.identity.exception.InvalidCredentialsException replay){rejected.incrementAndGet();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}));
            start.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2,successes.get());assertEquals(0,rejected.get());
        UUID family = jwt.sessionId(jwt.parse(issued.accessToken()));
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_refresh_tokens where session_id=? and used_at is null",
                Integer.class, family));
        assertTrue(sessions.accessAllowed(jwt.parse(issued.accessToken())));
    }

    @Test
    void lostRefreshResponseCanBeRetriedOnceWithoutRevokingTheFamily() {
        var user = user();
        var original = sessions.issue(user);
        var lost = sessions.rotate(user, jwt.parse(original.refreshToken()), original.refreshToken());
        var retried = assertDoesNotThrow(() -> sessions.rotate(user,
                jwt.parse(original.refreshToken()), original.refreshToken()));
        assertNotEquals(lost.refreshToken(), retried.refreshToken());
        assertTrue(sessions.accessAllowed(jwt.parse(retried.accessToken())));
        assertThrows(InvalidCredentialsException.class,
                () -> sessions.rotate(user, jwt.parse(original.refreshToken()), original.refreshToken()));
        assertFalse(sessions.accessAllowed(jwt.parse(retried.accessToken())));
    }

    @Test
    void olderTokenWithinTwentySecondsStillRevokesTheFamily() {
        var user = user();
        var first = sessions.issue(user);
        var second = sessions.rotate(user, jwt.parse(first.refreshToken()), first.refreshToken());
        var third = sessions.rotate(user, jwt.parse(second.refreshToken()), second.refreshToken());
        assertThrows(InvalidCredentialsException.class,
                () -> sessions.rotate(user, jwt.parse(first.refreshToken()), first.refreshToken()));
        assertFalse(sessions.accessAllowed(jwt.parse(third.accessToken())));
    }

    @Test
    void retryMustBelongToTheSameUserAndSession() {
        var owner = user();
        var issued = sessions.issue(owner);
        sessions.rotate(owner, jwt.parse(issued.refreshToken()), issued.refreshToken());
        assertThrows(InvalidCredentialsException.class,
                () -> sessions.rotate(user(), jwt.parse(issued.refreshToken()), issued.refreshToken()));
        assertTrue(sessions.accessAllowed(jwt.parse(issued.accessToken())));
    }
    @Configuration @Import(AuthSessions.class) static class Services {
        @Bean JwtService jwt() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();return new JwtService((RSAPrivateKey)key.getPrivate(),(RSAPublicKey)key.getPublic(),900000,604800000,1800000);}
    }
}
