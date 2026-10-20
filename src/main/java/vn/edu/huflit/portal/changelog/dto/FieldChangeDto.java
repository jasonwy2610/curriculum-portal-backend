package vn.edu.huflit.portal.changelog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldChangeDto {
    private String field;
    private Object oldValue;
    private Object newValue;
}
