package com.code.pal.project.supabase;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A project's link to the user's Supabase project; the access token is stored only encrypted. */
@Entity
@Table(name = "project_supabase_connections")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SupabaseConnection {
    @Id @Column(name = "project_id") UUID projectId;
    @Column(name = "project_ref", nullable = false, length = 40) String projectRef;
    @Column(nullable = false, length = 200) String url;
    @Column(name = "anon_key", nullable = false, length = 2000) String anonKey;
    @Column(name = "access_token_cipher", length = 4000) String accessTokenCipher;
    @Column(name = "connected_by", nullable = false) Long connectedBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
