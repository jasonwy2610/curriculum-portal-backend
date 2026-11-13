package vn.edu.huflit.portal.major.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.major.model.*;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import java.util.*;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/admin/majors/{majorId}/specializations")
@PreAuthorize("hasRole('ADMIN')")
public class MajorSpecializationController {
 private final MajorRepository majors;
 @Data public static class Input {
  @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,20}",message="Mã gồm 2-20 ký tự in hoa, số, _ hoặc -") private String code;
  @NotBlank private String name;
  private String status;
 }
 private Major major(String id){if(!ObjectId.isValid(id))throw new IllegalArgumentException("Mã ngành không hợp lệ");return majors.findById(new ObjectId(id)).orElseThrow(()->new IllegalArgumentException("Không tìm thấy ngành"));}
 @GetMapping public List<Specialization> list(@PathVariable String majorId){return Optional.ofNullable(major(majorId).getSpecializations()).orElse(List.of());}
 @PostMapping public Specialization create(@PathVariable String majorId,@Valid @RequestBody Input input){
  Major m=major(majorId);List<Specialization> list=new ArrayList<>(Optional.ofNullable(m.getSpecializations()).orElse(List.of()));
  if(list.stream().anyMatch(x->x.getCode().equalsIgnoreCase(input.getCode())))throw new IllegalArgumentException("Mã chuyên ngành đã tồn tại");
  if(list.stream().anyMatch(x->x.getName().equalsIgnoreCase(input.getName().trim())))throw new IllegalArgumentException("Tên chuyên ngành đã tồn tại");
  Specialization item=new Specialization(input.getCode(),input.getName().trim(),"ACTIVE");list.add(item);m.setSpecializations(list);majors.save(m);return item;
 }
 @PutMapping("/{code}") public Specialization update(@PathVariable String majorId,@PathVariable String code,@Valid @RequestBody Input input){
  Major m=major(majorId);List<Specialization> list=new ArrayList<>(Optional.ofNullable(m.getSpecializations()).orElse(List.of()));
  Specialization item=list.stream().filter(x->x.getCode().equals(code)).findFirst().orElseThrow(()->new IllegalArgumentException("Không tìm thấy chuyên ngành"));
  if(!code.equals(input.getCode()))throw new IllegalArgumentException("Không được thay mã chuyên ngành đang được tham chiếu");
  if(list.stream().anyMatch(x->x!=item&&x.getName().equalsIgnoreCase(input.getName().trim())))throw new IllegalArgumentException("Tên chuyên ngành đã tồn tại");
  if(!List.of("ACTIVE","INACTIVE").contains(input.getStatus()))throw new IllegalArgumentException("Trạng thái không hợp lệ");
  item.setName(input.getName().trim());item.setStatus(input.getStatus());m.setSpecializations(list);majors.save(m);return item;
 }
 @PatchMapping("/{code}/deactivate") public Specialization deactivate(@PathVariable String majorId,@PathVariable String code){
  Major m=major(majorId);Specialization item=Optional.ofNullable(m.getSpecializations()).orElse(List.of()).stream().filter(x->x.getCode().equals(code)).findFirst().orElseThrow(()->new IllegalArgumentException("Không tìm thấy chuyên ngành"));
  item.setStatus("INACTIVE");majors.save(m);return item;
 }
}
