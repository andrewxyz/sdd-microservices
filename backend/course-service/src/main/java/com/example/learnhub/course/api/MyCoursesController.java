package com.example.learnhub.course.api;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.application.MyCoursesService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/my/courses} — protected; never another instructor's courses (FR-012). */
@RestController
@RequestMapping("/api/v1/my/courses")
public class MyCoursesController {

    private final MyCoursesService myCoursesService;

    public MyCoursesController(MyCoursesService myCoursesService) {
        this.myCoursesService = myCoursesService;
    }

    @GetMapping
    public List<CourseFullView> mine(@RequestHeader(IdentityHeaders.USER_ID) UUID callerId) {
        return myCoursesService.listMine(callerId);
    }
}
