package com.example.learnhub.course.infrastructure;

import com.example.learnhub.course.domain.Course;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CourseRepository extends JpaRepository<Course, UUID>, JpaSpecificationExecutor<Course> {

    long countByCategoryId(UUID categoryId);

    List<Course> findByInstructorId(UUID instructorId);
}
