package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateKnowledgeGroupRequest {
    @NotBlank(message = "Tên nhóm kiến thức không được để trống")
    private String groupName;

    private Integer order;
}
