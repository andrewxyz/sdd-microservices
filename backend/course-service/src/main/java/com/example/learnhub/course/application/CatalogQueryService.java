package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CoursePublicView;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Level;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-001 to FR-005: the public catalog list — always restricted to {@code PUBLISHED} (FR-005), with
 * optional category/level/free/keyword filters and a choice of sort (research.md R4).
 *
 * <p>Maps to {@link CoursePublicView} inside the (read-only) transaction: {@code Course.lessons} is
 * lazy, and open-in-view is off, so mapping must happen before the entity is detached.
 */
@Service
public class CatalogQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final CourseRepository courses;

    public CatalogQueryService(CourseRepository courses) {
        this.courses = courses;
    }

    @Transactional(readOnly = true)
    public Page<CoursePublicView> search(int page, Integer size, String sort, UUID categoryId, Level level,
            Boolean free, String keyword) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Specification<Course> spec = alwaysPublished();

        if (categoryId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("categoryId"), categoryId));
        }
        if (level != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("level"), level));
        }
        if (free != null) {
            spec = free
                    ? spec.and((root, query, cb) -> cb.equal(root.get("priceCents"), 0))
                    : spec.and((root, query, cb) -> cb.greaterThan(root.get("priceCents"), 0));
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)));
        }

        Page<Course> result = courses.findAll(spec, PageRequest.of(page, pageSize, sortFor(sort)));
        return result.map(CoursePublicView::of);
    }

    private static Specification<Course> alwaysPublished() {
        return (root, query, cb) -> cb.equal(root.get("status"), CourseStatus.PUBLISHED);
    }

    private static Sort sortFor(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.DESC, "publishedAt");
        }
        return switch (sort) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "priceCents");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "priceCents");
            default -> Sort.by(Sort.Direction.DESC, "publishedAt");
        };
    }
}
