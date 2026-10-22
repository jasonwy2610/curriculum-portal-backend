package vn.edu.huflit.portal.service;
import vn.edu.huflit.portal.entity.Notification;
import vn.edu.huflit.portal.entity.User;
import java.util.List;
import java.util.Map;
public interface NotificationService {
 List<Map<String,Object>> listForStudent(User user);
 Map<String,Object> detailForStudent(User user, String id);
 Map<String,Object> markRead(User user, String id);
 List<Notification> listAll();
 Notification create(User actor, Notification n);
}
