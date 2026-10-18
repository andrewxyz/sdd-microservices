package com.example.learnhub.course.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The aggregate root: a single catalog offering (FR-008 to FR-018, FR-025 to FR-027). Lessons are
 * children of this aggregate — adding, editing, removing or reordering a lesson also increments this
 * entity's {@code version} (research.md R1), since they are saved together.
 */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    private UUID id;

    private String title;

    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    private Level level;

    private String language;

    @Column(name = "price_cents")
    private Integer priceCents;

    @Column(nullable = false)
    private String currency = "USD";

    @Column(name = "instructor_id", nullable = false, updatable = false)
    private UUID instructorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<Lesson> lessons = new ArrayList<>();

    protected Course() {
        // JPA
    }

    public Course(UUID id, UUID instructorId, Instant createdAt) {
        this.id = id;
        this.instructorId = instructorId;
        this.status = CourseStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void markPublished(Instant now) {
        this.status = CourseStatus.PUBLISHED;
        this.publishedAt = now;
        touch(now);
    }

    public void markArchived(Instant now) {
        this.status = CourseStatus.ARCHIVED;
        touch(now);
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Integer priceCents) {
        this.priceCents = priceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getInstructorId() {
        return instructorId;
    }

    public CourseStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<Lesson> getLessons() {
        return lessons;
    }
}
