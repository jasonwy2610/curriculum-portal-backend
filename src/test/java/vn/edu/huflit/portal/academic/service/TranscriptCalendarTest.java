package vn.edu.huflit.portal.academic.service;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.User;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptCalendarTest {
    final ObjectId id=new ObjectId("48982b5177b75b4f638ec2b3");
    User student(){return User.builder().curriculumId(id).cohort("K2023").chosenSpecializationCode("CNPM").build();}
    Curriculum tree(int size){return Curriculum.builder().id(id).version(1).cohort("K2023").semesters(
        java.util.stream.IntStream.rangeClosed(1,size).mapToObj(n->new Curriculum.SemesterItem(n,List.of())).toList()).build();}
    @Test void confirmedScheduleDoesNotLeakToOtherCohortsVersionsOrSpecializations(){
        var t=tree(8);var u=student();assertEquals(8,TranscriptCalendar.resolve(t,u).size());
        u.setChosenSpecializationCode("OTHER");assertTrue(TranscriptCalendar.resolve(t,u).isEmpty());
        u=student();u.setCohort("K2024");assertTrue(TranscriptCalendar.resolve(t,u).isEmpty());
        u=student();t.setVersion(2);assertTrue(TranscriptCalendar.resolve(t,u).isEmpty());
        t=tree(8);t.setId(new ObjectId());assertTrue(TranscriptCalendar.resolve(t,u).isEmpty());
    }
    @Test void unrelatedEightTermCurriculumIsNotEnoughToInferACalendar(){
        var t=tree(8);var u=student();t.setId(new ObjectId());u.setCurriculumId(t.getId());
        assertTrue(TranscriptCalendar.resolve(t,u).isEmpty());
    }
    @Test void explicitlyConfiguredSummerCalendarSupportsNonEightTermProgram(){
        var p=new Properties();p.setProperty(id+".1.CNPM.K2023","2023-1,2023-2,2023-3");
        assertEquals("2023-3",TranscriptCalendar.resolve(p,tree(3),student()).get(3));
    }
    @Test void incompleteDuplicateOutOfOrderOrUnknownSpecialTermsCannotProduceFakeYears(){
        String key=id+".1.CNPM.K2023";var p=new Properties();
        for(String value:List.of("2023-1,2023-2","2023-1,2023-1,2023-2","2023-2,2023-1,2023-3","2023-1,2023-2,2023-4")){
            p.setProperty(key,value);assertThrows(IllegalStateException.class,()->TranscriptCalendar.resolve(p,tree(3),student()));
        }
        assertThrows(IllegalStateException.class,()->TranscriptCalendar.resolve(tree(7),student()));
    }
}
