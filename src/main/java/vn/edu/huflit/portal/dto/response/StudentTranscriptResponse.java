package vn.edu.huflit.portal.dto.response;

import java.util.Date;
import java.util.List;

/** Academic activity credits are distinct from graduation-recognized credits. */
public record StudentTranscriptResponse(Summary summary, List<Semester> semesters,
        List<Attempt> attempts, List<Issue> issues, CurriculumPlan curriculumPlan) {
    /** Calendar years require a confirmed schedule scoped to the assigned curriculum version and tree. */
    public record CurriculumPlan(boolean available, String message, String curriculumId, Integer version,
                                 String specializationCode, List<PlannedSemester> semesters) {}
    /** Optional calendar code must come from an official schedule, never inferred from cohort or grades. */
    public record PlannedSemester(String semesterKey, int plannedSemesterNumber, List<PlannedCourse> courses, String academicSemesterCode) {
        public PlannedSemester(String semesterKey, int plannedSemesterNumber, List<PlannedCourse> courses) {
            this(semesterKey, plannedSemesterNumber, courses, null);
        }
    }
    public record PlannedCourse(String courseCode, String courseName, Integer credits, String state,
                                List<String> actualSemesterCodes, List<String> replacementCourseCodes) {}
    public record Summary(Double cumulativeGpa4, Integer gpaCredits, Integer academicEarnedCredits,
                          Integer graduationRecognizedCredits, int failedCourseCount) {}
    public record Semester(String semesterCode, String academicYear, Integer term,
                           Double gpa4, Integer gpaCredits, Integer attemptedCredits,
                           Integer earnedCredits, int attemptCount) {}
    public record Attempt(String recordId, String attemptId, String curriculumId, String courseCode,
                          String courseName, Integer credits, String semesterCode, String academicYear,
                          Integer term, Integer attemptNumber, Double score10, Double score4,
                          String letterGrade, String status, boolean retake, Date completedAt) {}
    public record Issue(String courseCode, String semesterCode, String field, String message) {}
}
