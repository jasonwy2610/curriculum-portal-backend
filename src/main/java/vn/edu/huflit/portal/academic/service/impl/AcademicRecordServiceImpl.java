package vn.edu.huflit.portal.academic.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.dto.request.AddAcademicAttemptRequest;
import vn.edu.huflit.portal.dto.response.AcademicRecordResponse;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;
import vn.edu.huflit.portal.academic.service.GradeConversionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Date;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.edu.huflit.portal.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AcademicRecordServiceImpl implements AcademicRecordService {

    private final AcademicRecordRepository academicRecordRepository;
    private final GradeConversionService gradeConversionService;
    private final ChangeLogService changeLogs;
    private final UserRepository users;

    @Override
    public List<AcademicRecordResponse> getMyAcademicRecords(ObjectId studentId) {
        return academicRecordRepository.findByStudentId(studentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public AcademicRecordResponse getMyAcademicRecord(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId
    ) {
        validateCourseCode(courseCode);

        return academicRecordRepository
                .findByStudentIdAndCourseCodeAndCurriculumId(
                        studentId, courseCode, curriculumId
                )
                .map(this::toResponse)
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy kết quả học tập của học phần " + courseCode
                ));
    }

    @Override
    public AcademicRecordResponse addAttempt(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId,
            AddAcademicAttemptRequest request
    ) {
        validateCourseCode(courseCode);

        AcademicRecord record = academicRecordRepository
                .findByStudentIdAndCourseCodeAndCurriculumId(
                        studentId, courseCode, curriculumId
                )
                .orElseGet(() -> AcademicRecord.builder()
                        .studentId(studentId)
                        .courseCode(courseCode)
                        .curriculumId(curriculumId)
                        .attempts(new ArrayList<>())
                        .build());

        List<AcademicRecord.AttemptItem> attempts = record.getAttempts();
        if (attempts == null) {
            attempts = new ArrayList<>();
            record.setAttempts(attempts);
        }

        int nextAttemptNumber = attempts.stream()
                .map(AcademicRecord.AttemptItem::getAttemptNumber)
                .filter(number -> number != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        if (request == null || request.getScore10() == null || !Double.isFinite(request.getScore10())
                || request.getScore10() < 0 || request.getScore10() > 10)
            throw new IllegalArgumentException("Điểm phải là số hữu hạn từ 0 đến 10");
        GradeConversionService.GradeResult grade =
                gradeConversionService.convert(request.getScore10());

        AcademicRecord.AttemptItem attempt = new AcademicRecord.AttemptItem();
        attempt.setAttemptId(UUID.randomUUID().toString());
        attempt.setCompletedAt(request.getCompletedAt());
        attempt.setSemesterCode(request.getSemesterCode());
        attempt.setScore10(request.getScore10());
        attempt.setLetterGrade(grade.letterGrade());
        attempt.setScore4(grade.score4());
        attempt.setAttemptNumber(
                request.getAttemptNumber() != null
                        ? request.getAttemptNumber()
                        : nextAttemptNumber
        );

        attempts.add(attempt);

        refreshHighest(record);
        academicRecordRepository.save(record);
        changeLogs.recordChange("AcademicRecord", record.getId(), "ADD_ATTEMPT",
            List.of(new ChangeLog.FieldChangeItem("attempts." + attempt.getAttemptId(), null,
                attempt.getSemesterCode() + ":" + attempt.getScore10())), actor());
        return toResponse(record);
    }

    @Override
    public AcademicRecordResponse correctAttempt(ObjectId recordId, String attemptId,
            Double correctedScore, String reason) {
        if (correctedScore == null || !Double.isFinite(correctedScore)
                || correctedScore < 0 || correctedScore > 10)
            throw new IllegalArgumentException("Điểm sửa phải nằm trong khoảng 0–10");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("Bắt buộc nhập lý do sửa điểm");
        AcademicRecord record = academicRecordRepository.findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kết quả học tập"));
        if (attemptId == null || attemptId.isBlank())
            throw new IllegalArgumentException("Bắt buộc chỉ rõ attemptId");
        if (record.getAttempts() == null) throw new IllegalArgumentException("Học phần chưa có lần học");
        AcademicRecord.AttemptItem attempt = record.getAttempts().stream()
            .filter(a -> attemptId.equals(a.getAttemptId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lần học; dữ liệu cũ cần gán attemptId"));
        Double oldScore = attempt.getScore10();
        if (oldScore != null && oldScore.equals(correctedScore))
            throw new IllegalArgumentException("Điểm mới không thay đổi");
        GradeConversionService.GradeResult grade = gradeConversionService.convert(correctedScore);
        attempt.setScore10(correctedScore);
        attempt.setLetterGrade(grade.letterGrade());
        attempt.setScore4(grade.score4());
        refreshHighest(record);
        academicRecordRepository.save(record);
        changeLogs.recordChange("AcademicRecord", record.getId(), "CORRECT_ATTEMPT", List.of(
            new ChangeLog.FieldChangeItem("attempts." + attemptId + ".score10", oldScore, correctedScore),
            new ChangeLog.FieldChangeItem("attempts." + attemptId + ".reason", null, reason)
        ), actor());
        return toResponse(record);
    }

    private void refreshHighest(AcademicRecord record) {
        AcademicRecord.AttemptItem best = record.getAttempts().stream()
            .filter(a -> a.getScore10() != null && Double.isFinite(a.getScore10())
                && a.getScore10() >= 0 && a.getScore10() <= 10)
            .max(Comparator.comparing(AcademicRecord.AttemptItem::getScore10)).orElse(null);
        if (best == null) {
            record.setHighestScore10(null); record.setHighestLetterGrade(null);
            record.setHighestScore4(null); record.setStatus("NOT_STUDIED");
            return;
        }
        GradeConversionService.GradeResult grade = gradeConversionService.convert(best.getScore10());
        record.setHighestScore10(best.getScore10());
        record.setHighestLetterGrade(grade.letterGrade());
        record.setHighestScore4(grade.score4());
        record.setStatus(grade.status());
    }

    private ObjectId actor() {
        String name = SecurityContextHolder.getContext().getAuthentication() == null
            ? null : SecurityContextHolder.getContext().getAuthentication().getName();
        if (name == null) return null;
        return users.findByEmail(name).map(u -> u.getId()).orElse(null);
    }

    private void validateCourseCode(String courseCode) {
        if (!StringUtils.hasText(courseCode)) {
            throw new RuntimeException("courseCode không được để trống");
        }
    }

    private AcademicRecordResponse toResponse(AcademicRecord record) {
        List<AcademicRecordResponse.AttemptResponse> attempts =
                record.getAttempts() == null
                        ? List.of()
                        : record.getAttempts().stream()
                        .map(attempt -> AcademicRecordResponse.AttemptResponse.builder()
                                .attemptId(attempt.getAttemptId())
                                .completedAt(attempt.getCompletedAt())
                                .semesterCode(attempt.getSemesterCode())
                                .score10(attempt.getScore10())
                                .letterGrade(attempt.getLetterGrade())
                                .score4(attempt.getScore4())
                                .attemptNumber(attempt.getAttemptNumber())
                                .build())
                        .toList();

        return AcademicRecordResponse.builder()
                .id(record.getId() != null ? record.getId().toHexString() : null)
                .studentId(record.getStudentId() != null
                        ? record.getStudentId().toHexString() : null)
                .courseCode(record.getCourseCode())
                .curriculumId(record.getCurriculumId() != null
                        ? record.getCurriculumId().toHexString() : null)
                .attempts(attempts)
                .highestScore10(record.getHighestScore10())
                .highestLetterGrade(record.getHighestLetterGrade())
                .highestScore4(record.getHighestScore4())
                .status(record.getStatus())
                .build();
    }
}
