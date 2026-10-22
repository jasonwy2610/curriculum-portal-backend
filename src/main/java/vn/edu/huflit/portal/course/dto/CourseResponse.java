package vn.edu.huflit.portal.course.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huflit.portal.course.model.Course;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseResponse {
    private String id;
    private String courseCode;
    private String courseName;
    private Integer credits;
    private Integer theoryHours;
    private Integer practiceHours;
    private String description;
    private String department;
    private String status;
    private Date createdAt;
    private Date updatedAt;

    public static CourseResponse fromEntity(Course course) {
        if (course == null) {
            return null;
        }
        return CourseResponse.builder()
                .id(course.getId() != null ? course.getId().toHexString() : null)
                .courseCode(course.getCourseCode())
                .courseName(course.getCourseName())
                .credits(course.getCredits())
                .theoryHours(course.getTheoryHours())
                .practiceHours(course.getPracticeHours())
                .description(course.getDescription())
                .department(course.getDepartment())
                .status(course.getStatus())
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }
}
