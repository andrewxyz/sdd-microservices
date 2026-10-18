package com.example.learnhub.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A child of exactly one {@link Course} (FR-010, FR-024). Only the owning instructor and
 * administrators may ever read {@code content} (FR-024); everyone permitted to see the course at all
 * may see {@code title}, {@code position} and {@code durationMinutes} (the public "outline," FR-006).
 */
@Entity
@Table(name = "lessons")
public class Lesson {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int position;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private String content;

    protected Lesson() {
        // JPA
    }

    public Lesson(UUID id, Course course, String title, int position, int durationMinutes, String content) {
        this.id = id;
        this.course = course;
        this.title = title;
        this.position = position;
        this.durationMinutes = durationMinutes;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
