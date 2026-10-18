package com.example.learnhub.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** A named grouping courses are organized under (FR-019 to FR-022). */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Version
    private long version;

    protected Category() {
        // JPA
    }

    public Category(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getVersion() {
        return version;
    }
}
