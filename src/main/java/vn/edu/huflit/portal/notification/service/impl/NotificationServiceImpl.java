package vn.edu.huflit.portal.notification.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.entity.Notification;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.notification.repository.NotificationRepository;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.notification.service.NotificationService;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
 private final NotificationRepository repository;
 private final UserRepository userRepository;

 @Override public List<Map<String,Object>> listForStudent(User user) {
   return repository.findByRecipientIdsContainingOrderByCreatedAtDesc(user.getId()).stream().map(n -> view(n,user)).toList();
 }
 @Override public Map<String,Object> detailForStudent(User user, String id) {
   Notification n = getVisible(user,id);
   return view(n,user);
 }
 @Override public Map<String,Object> markRead(User user, String id) {
   Notification n = getVisible(user,id);
   if(n.getReadBy()==null) n.setReadBy(new ArrayList<>());
   if(!n.getReadBy().contains(user.getId())) n.getReadBy().add(user.getId());
   repository.save(n);
   return view(n,user);
 }
 @Override public Map<String,Object> markUnread(User user, String id) {
   Notification n = getVisible(user, id);
   if (n.getReadBy() != null) n.getReadBy().removeIf(readId -> readId.equals(user.getId()));
   repository.save(n);
   return view(n, user);
 }
 @Override public List<Notification> listAll() { return repository.findAll().stream().sorted(Comparator.comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList(); }
 @Override public Notification create(User actor, Notification n) {
   if(n==null || !StringUtils.hasText(n.getTitle()) || !StringUtils.hasText(n.getMessage()))
      throw new IllegalArgumentException("Tiêu đề và nội dung thông báo không được để trống");
   if(n.getRecipientIds()==null || n.getRecipientIds().isEmpty())
      throw new IllegalArgumentException("Phải chỉ định ít nhất một recipientId");
   for(ObjectId id:n.getRecipientIds()) if(!userRepository.existsById(id)) throw new IllegalArgumentException("Không tìm thấy người nhận: "+id);
   n.setId(null); n.setCreatedBy(actor.getId()); n.setCreatedAt(new Date());
   if(n.getReadBy()==null) n.setReadBy(new ArrayList<>());
   return repository.save(n);
 }
 private Notification getVisible(User user,String id) {
   if(!ObjectId.isValid(id)) throw new IllegalArgumentException("ID thông báo không hợp lệ");
   Notification n=repository.findById(new ObjectId(id)).orElseThrow(()->new IllegalArgumentException("Không tìm thấy thông báo"));
   if(n.getRecipientIds()==null || !n.getRecipientIds().contains(user.getId()))
      throw new IllegalArgumentException("Bạn không có quyền xem thông báo này");
   return n;
 }
 private Map<String,Object> view(Notification n,User user) {
   Map<String,Object> m=new LinkedHashMap<>();
   m.put("id",n.getId()==null?null:n.getId().toHexString()); m.put("type",n.getType());
   m.put("title",n.getTitle()); m.put("message",n.getMessage());
   m.put("curriculumId",n.getCurriculumId()==null?null:n.getCurriculumId().toHexString());
   m.put("courseCode",n.getCourseCode()); m.put("replacementRuleId",n.getReplacementRuleId()==null?null:n.getReplacementRuleId().toHexString());
   m.put("createdAt",n.getCreatedAt()); m.put("read",n.getReadBy()!=null && n.getReadBy().contains(user.getId()));
   return m;
 }
}
