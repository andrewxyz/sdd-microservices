package com.example.learnhub.analytics.infrastructure;

import feign.FeignException;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Fails closed (spec FR-016): every failure of the ownership lookup, including a timeout, a server error
 * and an open circuit, becomes {@link OwnershipUnavailableException}, so no figure is ever produced without
 * a confirmed owner. A 404 on the single-course lookup is the one answer, not a failure.
 */
@Component
public class CourseOwnershipClientFallbackFactory implements FallbackFactory<CourseOwnershipClient> {

    @Override
    public CourseOwnershipClient create(Throwable cause) {
        return new CourseOwnershipClient() {
            @Override
            public List<InstructorCourseItem> listByInstructor(UUID instructorId) {
                throw new OwnershipUnavailableException("course list of an instructor", cause);
            }

            @Override
            public CourseOwner detail(UUID courseId) {
                if (cause instanceof FeignException feign && feign.status() == 404) {
                    throw new CourseNotFoundException(courseId);
                }
                throw new OwnershipUnavailableException("course " + courseId, cause);
            }
        };
    }
}
