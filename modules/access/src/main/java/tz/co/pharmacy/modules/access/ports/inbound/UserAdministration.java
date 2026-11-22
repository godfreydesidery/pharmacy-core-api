package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Members and roles of the current tenant, and the root administrators. */
public interface UserAdministration {

  /**
   * Display names of people by their actor reference (the sign-in email recorded as created_by):
   * name, else the email; references with no user are left out.
   */
  java.util.Map<String, String> displayNames(java.util.Collection<String> references);

  /** Display names of people by account uid: name, else the email; unknown uids are left out. */
  java.util.Map<UUID, String> displayNamesByUid(java.util.Collection<UUID> userUids);

  /** Newest first; {@code q} matches name or email. */
  Page<MemberView> members(String q, PageQuery query);

  /** A new email creates the user; an existing one gets a membership (409 already_a_member). */
  MemberView addMember(AddMemberCommand command);

  MemberView changeMember(UUID membershipUid, ChangeMemberCommand command);

  List<RoleView> roles();

  RoleView createRole(RoleCommand command);

  /** A system role keeps its code and name. */
  RoleView changeRole(UUID roleUid, RoleCommand command);

  /**
   * Seeds the default system roles in the current tenant. A new email becomes its ADMIN for every
   * branch at once; an email that already has an account is left for the caller to invite, so no
   * one is made a pharmacy's administrator without accepting (and the typed password is unused).
   */
  SeededTenant seedTenant(NewUserCommand administrator);

  /** Platform scope. */
  List<UserView> rootAdmins();

  /** Platform scope; 409 duplicate_email. */
  UserView createRootAdmin(NewUserCommand command);

  /**
   * Platform scope. Deactivating signs the person out everywhere. Never yourself, never the last
   * active root administrator: 422 last_root_admin / cannot_change_self.
   */
  UserView setRootAdminActive(UUID userUid, boolean active);

  /** Platform scope: takes away root rights (the account stays for any memberships). */
  void removeRootAdmin(UUID userUid);

  /** The signed-in person's own name and phone (E.164; 422 invalid_phone). */
  UserView changeOwnProfile(String name, String phone);

  /** The signed-in person. */
  UserView me();

  boolean anyRootAdmin();

  long userCount();
}
