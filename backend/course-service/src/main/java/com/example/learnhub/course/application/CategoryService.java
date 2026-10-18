package com.example.learnhub.course.application;

import com.example.learnhub.course.domain.Category;
import com.example.learnhub.course.infrastructure.CategoryRepository;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-019 to FR-022, FR-025: administrator-only category management (caller-role enforcement happens at
 * the gateway's {@code categories-admin} route, research.md R5/R9; this service re-checks nothing about
 * the caller, only the data invariants: uniqueness, in-use deletion guard, and optimistic concurrency —
 * analyze finding G1, since FR-025 explicitly covers category changes too).
 */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final CourseRepository courses;

    public CategoryService(CategoryRepository categories, CourseRepository courses) {
        this.categories = categories;
        this.courses = courses;
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categories.findAll();
    }

    @Transactional
    public Category create(String name) {
        if (categories.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category with this name already exists.");
        }
        return categories.save(new Category(UUID.randomUUID(), name));
    }

    @Transactional
    public Category rename(UUID categoryId, String newName, String ifMatch) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such category."));
        ConcurrencyControl.requireMatch(ifMatch, category.getVersion());

        boolean collidesWithAnother = categories.findByNameIgnoreCase(newName)
                .map(existing -> !existing.getId().equals(categoryId))
                .orElse(false);
        if (collidesWithAnother) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category with this name already exists.");
        }

        category.setName(newName);
        return categories.save(category);
    }

    @Transactional
    public void delete(UUID categoryId, String ifMatch) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such category."));
        ConcurrencyControl.requireMatch(ifMatch, category.getVersion());

        long courseCount = courses.countByCategoryId(categoryId);
        if (courseCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    courseCount + " course(s) still reference this category.");
        }
        categories.delete(category);
    }
}
