package vn.edu.huflit.portal.dto.response;

import java.util.Date;
import java.util.List;

/** Academic activity credits are distinct from graduation-recognized credits. */
public record StudentTranscriptResponse(Summary summary, List<Semester> semesters,
        List<Attempt> attempts, List<Issue> issues) {
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
