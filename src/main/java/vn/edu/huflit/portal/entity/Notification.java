package vn.edu.huflit.portal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notifications")
public class Notification {
    @Id
    private ObjectId id;

    private String type; // "COURSE_REPLACEMENT" | "CURRICULUM_CHANGE"
    private String title;
    private String message;
    private ObjectId curriculumId;
    private String courseCode;

    private List<ObjectId> recipientIds; // Danh sách ID sinh viên nhận thông báo
    private List<ObjectId> readBy;       // Danh sách ID sinh viên đã đọc

    private ObjectId createdBy;

    @CreatedDate
    private Date createdAt;
}