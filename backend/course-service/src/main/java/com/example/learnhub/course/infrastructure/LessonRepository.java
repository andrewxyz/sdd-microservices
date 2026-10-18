package com.example.learnhub.course.infrastructure;

import com.example.learnhub.course.domain.Lesson;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findByCourseIdOrderByPosition(UUID courseId);

    long countByCourseId(UUID courseId);
}
