package tz.co.pharmacy.platform.persistence.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import tz.co.pharmacy.platform.persistence.RecordedEntity;

/**
 * Writes an audit entry for every insert, update and delete of a {@link RecordedEntity}: the new
 * values on CREATE, the changed properties before and after on UPDATE, the last values on DELETE,
 * with secrets masked and bookkeeping columns left out. An update that changes nothing but
 * bookkeeping is not recorded. Entities annotated {@link AuditIgnore} are skipped.
 */
public class EntityAuditListener
    implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

  private final AuditWriter writer;

  EntityAuditListener(AuditWriter writer) {
    this.writer = writer;
  }

  @Override
  public void onPostInsert(PostInsertEvent event) {
    if (!audited(event.getEntity())) {
      return;
    }
    Map<String, Object> after = values(event.getPersister(), event.getState(), null);
    writer.insert("CREATE", type(event.getPersister()), uid(event.getEntity()), null, after);
  }

  @Override
  public void onPostUpdate(PostUpdateEvent event) {
    if (!audited(event.getEntity())) {
      return;
    }
    String[] names = event.getPersister().getPropertyNames();
    Object[] old = event.getOldState();
    Object[] now = event.getState();
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    for (int i : changed(event, names.length)) {
      String name = names[i];
      if (AuditValues.IGNORED.contains(name)) {
        continue;
      }
      Object previous = old == null ? null : old[i];
      Object current = now[i];
      if (old != null && Objects.equals(previous, current)) {
        continue;
      }
      before.put(name, AuditValues.value(name, previous));
      after.put(name, AuditValues.value(name, current));
    }
    if (after.isEmpty()) {
      return;
    }
    Map<String, Object> current = values(event.getPersister(), now, null);
    writer.insert(
        "UPDATE",
        type(event.getPersister()),
        uid(event.getEntity()),
        before,
        after,
        AuditValues.label(current, before));
  }

  @Override
  public void onPostDelete(PostDeleteEvent event) {
    if (!audited(event.getEntity())) {
      return;
    }
    Map<String, Object> before = values(event.getPersister(), event.getDeletedState(), null);
    writer.insert("DELETE", type(event.getPersister()), uid(event.getEntity()), before, null);
  }

  @Override
  public boolean requiresPostCommitHandling(EntityPersister persister) {
    return false;
  }

  private static int[] changed(PostUpdateEvent event, int count) {
    if (event.getDirtyProperties() != null) {
      return event.getDirtyProperties();
    }
    int[] all = new int[count];
    for (int i = 0; i < count; i++) {
      all[i] = i;
    }
    return all;
  }

  private static Map<String, Object> values(
      EntityPersister persister, Object[] state, Map<String, Object> into) {
    Map<String, Object> values = into == null ? new LinkedHashMap<>() : into;
    if (state == null) {
      return values;
    }
    String[] names = persister.getPropertyNames();
    for (int i = 0; i < names.length && i < state.length; i++) {
      if (!AuditValues.IGNORED.contains(names[i]) && state[i] != null) {
        values.put(names[i], AuditValues.value(names[i], state[i]));
      }
    }
    return values;
  }

  private static boolean audited(Object entity) {
    return entity instanceof RecordedEntity
        && !entity.getClass().isAnnotationPresent(AuditIgnore.class);
  }

  private static String type(EntityPersister persister) {
    String name = persister.getEntityName();
    return name.substring(name.lastIndexOf('.') + 1);
  }

  private static UUID uid(Object entity) {
    return entity instanceof RecordedEntity recorded ? recorded.getUid() : null;
  }
}
