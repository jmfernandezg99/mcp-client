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
@Table(name = "workspaces", uniqueConstraints = {
        @UniqueConstraint(name = "uk_workspaces_user", columnNames = {"user_id"}),
        @UniqueConstraint(name = "uk_workspaces_runtime_port", columnNames = {"runtime_port"})
})
public class Workspace extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(nullable = false, length = 120)
    public String name;

    @Column(nullable = false, length = 40)
    public String status;

    @Column(name = "runtime_port")
    public Integer runtimePort;

    @Column(name = "runtime_url", length = 500)
    public String runtimeUrl;

    @Column(name = "config_path", length = 1000)
    public String configPath;

    @Column(name = "startup_command", length = 4000)
    public String startupCommand;

    @Column(name = "last_error", length = 2000)
    public String lastError;

    @Column(name = "last_applied_at")
    public LocalDateTime lastAppliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static Workspace findByUserId(UUID userId) {
        return find("user.id", userId).firstResult();
    }

    public static List<Integer> listAssignedPorts() {
        return find("select runtimePort from Workspace where runtimePort is not null order by runtimePort asc")
                .project(Integer.class)
                .list();
    }
}
