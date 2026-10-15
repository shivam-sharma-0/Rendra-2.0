package com.code.pal.identity.security;

import com.code.pal.identity.domain.User;
import com.code.pal.identity.dto.AuthResponse;
import com.code.pal.identity.dto.UserResponse;
import com.code.pal.identity.exception.InvalidCredentialsException;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.HexFormat;

/** Hash-only refresh families. Reuse revocation commits before reporting an invalid session. */
@Service
public final class AuthSessions {
    private final JdbcTemplate jdbc;private final JwtService jwt;
    private final TransactionTemplate issuing,rotating;
    public AuthSessions(JdbcTemplate jdbc,JwtService jwt,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.jwt=jwt;issuing=new TransactionTemplate(manager);rotating=new TransactionTemplate(manager);
        rotating.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);rotating.setTimeout(5);
    }
    public AuthResponse issue(User user) {
        if(user.getId()==null || !Boolean.TRUE.equals(user.getEnabled()))throw invalid();
        return issuing.execute(status->{UUID id=UUID.randomUUID();String refresh=jwt.generateRefreshToken(user,id);
            Instant expiry=jwt.parse(refresh).getExpiration().toInstant();
            jdbc.update("insert into auth_sessions(id,user_id,expires_at) values (?,?,?)",id,user.getId(),java.sql.Timestamp.from(expiry));
            saveRefresh(id,refresh,expiry);return response(user,id,refresh);
        });
    }
    private record RefreshRow(UUID session, Instant expiry, boolean used, boolean revoked, String replacement) {}
    public AuthResponse rotate(User user,Claims claims,String previous) {
        UUID session = jwt.sessionId(claims);
        if (session == null) {
            throw invalid();
        }
        String previousHash = hash(previous);
        AuthResponse result = rotating.execute(status -> {
            var rows = jdbc.query("select s.id,s.expires_at,r.used_at is not null as used,"
                            + "s.revoked_at is not null as revoked,r.replacement_hash "
                            + "from auth_refresh_tokens r join auth_sessions s on s.id=r.session_id "
                            + "where r.token_hash=? and s.id=? and s.user_id=? for update of s,r",
                    (row, index) -> new RefreshRow(row.getObject("id", UUID.class),
                            row.getTimestamp("expires_at").toInstant(), row.getBoolean("used"),
                            row.getBoolean("revoked"), row.getString("replacement_hash")),
                    previousHash, session, user.getId());
            if (rows.isEmpty()) {
                return null;
            }
            RefreshRow row = rows.getFirst();
            if (row.revoked() || !row.expiry().isAfter(Instant.now())) {
                return null;
            }
            String currentHash = previousHash;
            if (row.used()) {
                if (row.replacement() == null || !consumeRetry(previousHash)
                        || !consumeCurrentReplacement(session, row.replacement())) {
                    revoke(session);
                    return null;
                }
                currentHash = row.replacement();
            }
            return rotateCurrent(user, session, currentHash, row.expiry());
        });
        if (result == null) {
            throw invalid();
        }
        return result;
    }

    private boolean consumeRetry(String previousHash) {
        return jdbc.update("update auth_refresh_tokens set retry_used_at=clock_timestamp() "
                        + "where token_hash=? and retry_used_at is null "
                        + "and used_at>=clock_timestamp()-interval '20 seconds'", previousHash) == 1;
    }

    private boolean consumeCurrentReplacement(UUID session, String replacement) {
        return jdbc.update("update auth_refresh_tokens set used_at=clock_timestamp() "
                        + "where token_hash=? and session_id=? and used_at is null and expires_at>clock_timestamp()",
                replacement, session) == 1;
    }

    private AuthResponse rotateCurrent(User user, UUID session, String currentHash, Instant expiry) {
        String next = jwt.generateRefreshToken(user, session);
        saveRefresh(session, next, expiry);
        if (jdbc.update("update auth_refresh_tokens set used_at=coalesce(used_at,clock_timestamp()),"
                + "replacement_hash=? where token_hash=? and session_id=?", hash(next), currentHash, session) != 1) {
            throw new IllegalStateException("Refresh token rotation lost its session binding");
        }
        return response(user, session, next);
    }
    private void saveRefresh(UUID session,String token,Instant expiry){jdbc.update("insert into auth_refresh_tokens(token_hash,session_id,expires_at) values (?,?,?)",hash(token),session,java.sql.Timestamp.from(expiry));}
    private AuthResponse response(User user,UUID id,String refresh){return new AuthResponse(jwt.generateAccessToken(user,id),refresh,UserResponse.from(user));}
    public boolean accessAllowed(Claims claims) {
        try {
            if(!JwtService.ACCESS.equals(jwt.tokenType(claims)))return false;UUID id=jwt.sessionId(claims);if(id==null)return false;
            return jdbc.queryForObject("select count(*) from auth_sessions s join users u on u.id=s.user_id where s.id=? and s.user_id=? and s.revoked_at is null and s.expires_at>clock_timestamp() and u.enabled=true and u.email=? and u.public_id=? and u.role=?",
                    Integer.class,id,claims.get("uid",Long.class),claims.getSubject(),UUID.fromString(claims.get("pub",String.class)),claims.get("role",String.class))==1;
        }catch(RuntimeException unavailable){return false;}
    }
    public boolean active(UUID session,Long user) {
        if(session==null || user==null)return false;
        return jdbc.queryForObject("select count(*) from auth_sessions s join users u on u.id=s.user_id where s.id=? and s.user_id=? and s.revoked_at is null and s.expires_at>clock_timestamp() and u.enabled=true",Integer.class,session,user)==1;
    }
    public void logout(String token) {
        if(token==null || token.isBlank())return;
        final UUID id;
        try {var claims=jwt.parse(token);if(!JwtService.REFRESH.equals(jwt.tokenType(claims)))return;id=jwt.sessionId(claims);if(id==null)return;}
        catch(io.jsonwebtoken.JwtException|IllegalArgumentException invalid){return;}
        rotating.executeWithoutResult(status->{if(jdbc.queryForObject("select count(*) from auth_refresh_tokens where session_id=? and token_hash=?",Integer.class,id,hash(token))==1)revoke(id);});
    }
    private void revoke(UUID id){jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,clock_timestamp()) where id=?",id);}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${app.auth.session-cleanup-ms:3600000}")
    public void prune(){jdbc.update("delete from auth_sessions where expires_at<clock_timestamp()-interval '1 day'");}
    private static InvalidCredentialsException invalid(){return new InvalidCredentialsException("Session expired or revoked. Please sign in again.");}
    private static String hash(String token){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
