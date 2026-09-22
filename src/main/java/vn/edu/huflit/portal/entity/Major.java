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
@Document(collection = "majors")
public class Major {
    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String code; // "7480201"

    private String name;
    private String description;
    private String status; // "ACTIVE" | "INACTIVE"

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}