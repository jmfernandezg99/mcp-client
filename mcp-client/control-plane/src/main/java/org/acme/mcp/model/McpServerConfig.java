package org.acme.mcp.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "mcp_server_configs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mcp_server_configs_user_name", columnNames = {"user_id", "name"}),
        @UniqueConstraint(name = "uk_mcp_server_configs_user_url", columnNames = {"user_id", "url"})
})
public class McpServerConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(nullable = false, length = 120)
    public String name;

    @Column(nullable = false, length = 1000)
    public String url;

    @Column(name = "published", nullable = false)
    public boolean published;

    @Column(name = "published_at")
    public LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static List<McpServerConfig> listByUserId(UUID userId) {
        return list("user.id = ?1 order by createdAt asc", userId);
    }

    public static List<McpServerConfig> listPublished() {
        return list("published = true order by createdAt asc");
    }

    public static McpServerConfig findByUserIdAndName(UUID userId, String name) {
        return find("user.id = ?1 and name = ?2", userId, name).firstResult();
    }

    public static McpServerConfig findByUserIdAndUrl(UUID userId, String url) {
        return find("user.id = ?1 and url = ?2", userId, url).firstResult();
    }
}
