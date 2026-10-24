package vn.edu.huflit.portal.academic.service.impl;

import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.academic.service.GradeConversionService;

import static org.junit.jupiter.api.Assertions.*;

class GradeConversionServiceImplTest {
    private final GradeConversionService service = new GradeConversionServiceImpl();

    @Test
    void convertsEveryHuFlitGradeBand() {
        assertGrade(9.0, "A+", 4.0, "PASSED");
        assertGrade(8.5, "A", 4.0, "PASSED");
        assertGrade(8.0, "B+", 3.5, "PASSED");
        assertGrade(7.0, "B", 3.0, "PASSED");
        assertGrade(6.0, "C+", 2.5, "PASSED");
        assertGrade(5.5, "C", 2.0, "PASSED");
        assertGrade(5.0, "D+", 1.5, "PASSED");
        assertGrade(4.0, "D", 1.0, "PASSED");
        assertGrade(3.9, "F", 0.0, "FAILED");
        assertGrade(0.0, "F", 0.0, "FAILED");
        assertGrade(10.0, "A+", 4.0, "PASSED");
    }

    @Test
    void rejectsScoresOutsideZeroToTen() {
        assertThrows(IllegalArgumentException.class, () -> service.convert(-0.1));
        assertThrows(IllegalArgumentException.class, () -> service.convert(10.1));
    }

    private void assertGrade(double score, String letter, double score4, String status) {
        GradeConversionService.GradeResult result = service.convert(score);
        assertEquals(letter, result.letterGrade(), "letter grade at score " + score);
        assertEquals(score4, result.score4(), 0.0001, "GPA at score " + score);
        assertEquals(status, result.status(), "pass status at score " + score);
    }
}
