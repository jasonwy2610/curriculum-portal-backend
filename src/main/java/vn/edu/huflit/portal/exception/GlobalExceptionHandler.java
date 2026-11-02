package vn.edu.huflit.portal.exception;

import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.edu.huflit.portal.dto.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<String>> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMsg = ex.getBindingResult().getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .findFirst()
                .orElse("Dữ liệu đầu vào không hợp lệ. Vui lòng kiểm tra lại thông tin.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(errorMsg));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<String>> handleDuplicateKeyException(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(duplicateMessage(ex)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<String>> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Bạn không có quyền thực hiện thao tác này. Vui lòng liên hệ quản trị viên nếu cần hỗ trợ."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<String>> handleRuntimeException(RuntimeException ex) {
        if (isDuplicateKey(ex)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(duplicateMessage(ex)));
        }
        String message = safeBusinessMessage(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<String>> handleGeneralException(Exception ex) {
        // Keep technical details in server logs only; never expose stack traces or DB internals to users.
        ex.printStackTrace();
        if (isDuplicateKey(ex)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(duplicateMessage(ex)));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Hệ thống chưa thể xử lý yêu cầu. Vui lòng thử lại sau hoặc liên hệ quản trị viên."));
    }

    private boolean isDuplicateKey(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            String className = current.getClass().getName();
            if ((message != null && (message.contains("E11000") || message.toLowerCase().contains("duplicate key")))
                    || className.contains("DuplicateKey") || className.contains("MongoWriteException")
                    || className.contains("MongoBulkWriteException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String duplicateMessage(Throwable ex) {
        String details = "";
        Throwable current = ex;
        while (current != null && details.isBlank()) {
            if (current.getMessage() != null) details = current.getMessage();
            current = current.getCause();
        }
        if (details.contains("curriculums") && details.contains("cohort")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("cohort\\s*[:=]\\s*[\\{]?\\s*['\\\"]?([^,'\\\"}\\s]+)")
                    .matcher(details);
            String cohort = matcher.find() ? matcher.group(1) : "đã chọn";
            return "Khóa " + cohort + " đã có chương trình đào tạo thuộc ngành này. "
                    + "Vui lòng kiểm tra CTĐT hiện có hoặc chỉnh sửa chương trình đã tạo.";
        }
        return "Thông tin bạn nhập đã tồn tại trong hệ thống. Vui lòng kiểm tra lại mã, khóa hoặc dữ liệu liên quan.";
    }

    private String safeBusinessMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Dữ liệu chưa hợp lệ. Vui lòng kiểm tra lại thông tin.";
        }
        String lower = message.toLowerCase();
        if (lower.contains("e11000") || lower.contains("mongo") || lower.contains("write operation error")
                || lower.contains("org.springframework") || lower.contains("java.lang")) {
            return "Dữ liệu bị trùng hoặc không thể lưu. Vui lòng kiểm tra thông tin đã tồn tại và thử lại.";
        }
        return message;
    }
}
