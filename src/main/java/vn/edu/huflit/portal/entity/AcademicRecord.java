package vn.edu.huflit.portal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "academicRecords")
public class AcademicRecord {
    @Id
    private ObjectId id;

    private ObjectId studentId; // Trỏ sang users._id
    private String courseCode;
    private ObjectId curriculumId;

    private List<AttemptItem> attempts;

    // Cache kết quả cao nhất để truy vấn nhanh (O(1))
    private Double highestScore10;
    private String highestLetterGrade;
    private Double highestScore4;
    private String status; // "PASSED" | "FAILED" | "NOT_STUDIED"

    @LastModifiedDate
    private Date updatedAt;

    @Data
    public static class AttemptItem {
        private String semesterCode;
        private Double score10;
        private String letterGrade;
        private Double score4;
        private Integer attemptNumber;
    }
}