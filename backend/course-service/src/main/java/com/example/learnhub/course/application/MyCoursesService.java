package com.example.learnhub.course.application;

import com.example.learnhub.course.api.CourseFullView;
import com.example.learnhub.course.infrastructure.CourseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-012: an instructor's own courses, in every status. */
@Service
public class MyCoursesService {

    private final CourseRepository courses;

    public MyCoursesService(CourseRepository courses) {
        this.courses = courses;
    }

    @Transactional(readOnly = true)
    public List<CourseFullView> listMine(UUID callerId) {
        return courses.findByInstructorId(callerId).stream().map(CourseFullView::of).toList();
    }
}
