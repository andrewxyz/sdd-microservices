package com.example.learnhub.auth.domain;

/** The three account roles (FR-012). Registration always assigns {@link #STUDENT} (FR-004). */
public enum Role {
    STUDENT,
    INSTRUCTOR,
    ADMINISTRATOR
}
