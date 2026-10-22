package vn.edu.huflit.portal.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class AdminAddAcademicAttemptRequest {
 @NotBlank private String studentId;
 @NotBlank private String courseCode;
 @NotBlank private String curriculumId;
 @Valid private AddAcademicAttemptRequest attempt;
}
