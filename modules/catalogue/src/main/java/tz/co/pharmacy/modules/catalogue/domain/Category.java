package tz.co.pharmacy.modules.catalogue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "catalogue", name = "categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends TenantEntity {

  private String name;
  private String description;

  /** The parent category, or null at the top of the tree; at most three levels deep. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  private Category parent;

  public Category(String name, String description, Category parent) {
    change(name, description, parent);
  }

  public void change(String name, String description, Category parent) {
    this.name = name;
    this.description = description;
    this.parent = parent;
  }

  /** 1 for a top-level category. */
  public int depth() {
    int depth = 1;
    for (Category up = parent; up != null; up = up.getParent()) {
      depth++;
    }
    return depth;
  }

  /** Whether {@code other} is this category or one of its ancestors. */
  public boolean isSelfOrAncestor(Category other) {
    for (Category up = this; up != null; up = up.getParent()) {
      if (up.getUid().equals(other.getUid())) {
        return true;
      }
    }
    return false;
  }

  /** The names from the top of the tree down to this category, joined with " › ". */
  public String path() {
    String path = name;
    for (Category up = parent; up != null; up = up.getParent()) {
      path = up.getName() + " › " + path;
    }
    return path;
  }
}
