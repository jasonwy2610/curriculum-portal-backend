package vn.edu.huflit.portal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;
    private String fullName;
    private String role; // "STUDENT" | "ADMIN"
    private String status; // "ACTIVE" | "INACTIVE"

    // Thuộc tính riêng cho role = STUDENT
    private String studentCode;
    private ObjectId majorId;
    private String cohort;
    private ObjectId curriculumId;
    private String chosenSpecializationCode;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}