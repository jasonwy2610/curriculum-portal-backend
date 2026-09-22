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
@Document(collection = "courses")
public class Course {
    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String courseCode; // Business key duy nhất

    private String courseName;
    private Integer credits;
    private Integer theoryHours;
    private Integer practiceHours;
    private String description;
    private String department;
    private String status; // "ACTIVE" | "INACTIVE"

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}