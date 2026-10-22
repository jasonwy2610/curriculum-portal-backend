package vn.edu.huflit.portal.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AcademicRecordResponse {

    private String id;
    private String studentId;
    private String courseCode;
    private String curriculumId;
    private List<AttemptResponse> attempts;

    private Double highestScore10;
    private String highestLetterGrade;
    private Double highestScore4;
    private String status;

    @Data
    @Builder
    public static class AttemptResponse {
        private String semesterCode;
        private Double score10;
        private String letterGrade;
        private Double score4;
        private Integer attemptNumber;
    }
}
