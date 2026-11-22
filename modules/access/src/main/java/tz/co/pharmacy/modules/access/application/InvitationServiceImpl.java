package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Invitation;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Role;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.InvitationRepository;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.RoleRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationPreview;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationView;
import tz.co.pharmacy.modules.access.ports.inbound.InviteCommand;
import tz.co.pharmacy.modules.access.ports.inbound.SignInOutcome;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.TenantSwitch;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Invitations by email. The invitee has no session, so row-level security shows them exactly their
 * invitation by the token hash the transaction names ({@code pharmacy.invitation_token_hash}).
 * Accepting switches into the tenant for the membership write, as registering a tenant does.
 */
@Service
class InvitationServiceImpl implements InvitationService {

  static final int VALID_DAYS = 7;
  private static final String TOKEN_SETTING = "pharmacy.invitation_token_hash";

  private final InvitationRepository invitations;
  private final RoleRepository roles;
  private final UserRepository users;
  private final MembershipRepository memberships;
  private final Passwords passwords;
  private final SessionIssuer issuer;
  private final TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;
  private final String webUrl;

  private final Clock clock;

  InvitationServiceImpl(
      InvitationRepository invitations,
      RoleRepository roles,
      UserRepository users,
      MembershipRepository memberships,
      Passwords passwords,
      SessionIssuer issuer,
      TenantSwitch tenantSwitch,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl,
      Clock clock) {
    this.clock = clock;
    this.invitations = invitations;
    this.roles = roles;
    this.users = users;
    this.memberships = memberships;
    this.passwords = passwords;
    this.issuer = issuer;
    this.tenantSwitch = tenantSwitch;
    this.audit = audit;
    this.notices = notices;
    this.jdbc = new JdbcTemplate(dataSource);
    this.writes = new TransactionTemplate(transactionManager);
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setReadOnly(true);
    this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
  }

  @Override
  @Transactional
  public InvitationView invite(InviteCommand command) {
    Checks.required(command, "invitation");
    UUID tenant = TenantContext.requireTenant();
    String email = Checks.email(command.email(), "email");
    String name = Checks.text(command.name(), "name", 120);
    Role role = role(command.roleUid());
    Set<UUID> branches = branches(command.allBranches(), command.branchUids());
    Optional<User> existing = users.findByEmail(email);
    if (existing.isPresent()
        && memberships
            .findByUserUidAndTenantUid(existing.get().getUid(), tenant)
            .filter(Membership::isActive)
            .isPresent()) {
      throw Refusal.conflict("already_a_member", email + " is already a member of this tenant.");
    }
    Instant now = clock.instant();
    invitations
        .findAllByTenantUidAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(tenant, email)
        .forEach(previous -> previous.revoke(now));
    String token = "piv_" + Tokens.newToken().substring(4);
    String inviter = ExecutionContext.actor().name();
    Invitation invitation =
        invitations.save(
            new Invitation(
                Tokens.hash(token),
                email,
                name,
                role,
                command.allBranches(),
                branches,
                inviter,
                now.plus(Duration.ofDays(VALID_DAYS))));
    String link = webUrl + "/accept-invitation?token=" + token;
    notices.ifAvailable(
        notice ->
            notice.invitation(tenant, name, email, inviter, role.getName(), link, VALID_DAYS));
    return view(invitation, now);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<InvitationView> list(boolean pendingOnly, PageQuery query) {
    Instant now = clock.instant();
    org.springframework.data.jpa.domain.Specification<Invitation> filter =
        Specs.equal("tenantUid", TenantContext.requireTenant());
    if (pendingOnly) {
      filter =
          filter.and(
              (root, criteria, cb) ->
                  cb.and(
                      cb.isNull(root.get("acceptedAt")),
                      cb.isNull(root.get("revokedAt")),
                      cb.greaterThan(root.get("expiresAt"), now)));
    }
    return Keyset.page(invitations, filter, query, invitation -> view(invitation, now));
  }

  @Override
  @Transactional
  public void revoke(UUID invitationUid) {
    invitations
        .findByUidAndTenantUid(invitationUid, TenantContext.requireTenant())
        .orElseThrow(() -> Refusal.notFound("invitation_not_found", "No such invitation."))
        .revoke(clock.instant());
  }

  @Override
  public InvitationPreview preview(String token) {
    String hash = Tokens.hash(token == null ? "" : token.trim());
    InvitationPreview preview =
        reads.execute(
            status -> {
              nameToken(hash);
              Invitation invitation =
                  invitations.findByTokenHash(hash).orElseThrow(InvitationServiceImpl::notFound);
              return new InvitationPreview(
                  invitation.getEmail(),
                  invitation.getName(),
                  invitation.getTenantUid(),
                  invitation.getRole().getName(),
                  invitation.getInvitedBy(),
                  invitation.getExpiresAt(),
                  invitation.status(clock.instant()),
                  users.findByEmail(invitation.getEmail()).isPresent());
            });
    if (preview == null) {
      throw notFound();
    }
    return preview;
  }

  @Override
  public SignInOutcome accept(String token, String name, String password, ClientInfo client) {
    String hash = Tokens.hash(token == null ? "" : token.trim());
    Instant now = clock.instant();
    User user =
        ExecutionContext.callAs(
            SessionIssuer.serviceFrame(),
            () ->
                writes.execute(
                    status -> {
                      Invitation invitation = open(hash, now);
                      if (users.findByEmail(invitation.getEmail()).isPresent()) {
                        // An existing account joins signed in (join), so its second factor and
                        // lockout apply as at any sign-in.
                        throw Refusal.conflict(
                            "account_exists",
                            invitation.getEmail()
                                + " already has an account: sign in, then accept the invitation.");
                      }
                      User member =
                          users.save(
                              new User(
                                  invitation.getEmail(),
                                  name == null || name.isBlank()
                                      ? invitation.getName()
                                      : Checks.text(name, "name", 120),
                                  passwords.hash(password, "password"),
                                  false));
                      admit(invitation, member, now);
                      return member;
                    }));
    if (user == null) {
      throw notFound();
    }
    return new SignInOutcome.Signed(issuer.issue(user, client, "INVITATION"));
  }

  @Override
  public Joined join(String token, UUID userUid) {
    String hash = Tokens.hash(token == null ? "" : token.trim());
    Instant now = clock.instant();
    User user =
        users
            .findByUid(Checks.required(userUid, "userUid"))
            .filter(User::isActive)
            .orElseThrow(() -> Refusal.unauthenticated("unauthenticated", "Sign in first."));
    if (user.isManaged()) {
      throw Refusal.invalid(
          "managed_login_cannot_join",
          "A pharmacy's staff login works only in that pharmacy. Sign in with your own account,"
              + " or create one, to accept this invitation.",
          "token");
    }
    Joined joined =
        ExecutionContext.callAs(
            SessionIssuer.userFrame(user),
            () ->
                writes.execute(
                    status -> {
                      Invitation invitation = open(hash, now);
                      Optional<Membership> current =
                          tenantSwitch.within(
                              invitation.getTenantUid(),
                              () ->
                                  memberships.findByUserUidAndTenantUid(
                                      user.getUid(), invitation.getTenantUid()));
                      if (current.filter(Membership::isActive).isPresent()) {
                        throw Refusal.conflict(
                            "already_a_member", "You already work in this pharmacy.");
                      }
                      admit(invitation, user, now);
                      return new Joined(invitation.getTenantUid(), invitation.getRole().getName());
                    }));
    if (joined == null) {
      throw notFound();
    }
    return joined;
  }

  /** The invitation of {@code hash}, still open; 404 or 422 invitation_not_open. */
  private Invitation open(String hash, Instant now) {
    nameToken(hash);
    Invitation invitation =
        invitations.findByTokenHash(hash).orElseThrow(InvitationServiceImpl::notFound);
    if (!invitation.openAt(now)) {
      throw Refusal.invalid(
          "invitation_not_open",
          "This invitation was already used, revoked or has expired.",
          "token");
    }
    return invitation;
  }

  /** Gives {@code user} the invitation's membership (or renews an old one) and closes it. */
  private void admit(Invitation invitation, User user, Instant now) {
    Role role = invitation.getRole();
    tenantSwitch.within(
        invitation.getTenantUid(),
        () -> {
          Optional<Membership> current =
              memberships.findByUserUidAndTenantUid(user.getUid(), invitation.getTenantUid());
          if (current.isPresent()) {
            current
                .get()
                .change(role, invitation.isAllBranches(), invitation.branchUidList(), true);
          } else {
            memberships.save(
                new Membership(
                    user.getUid(), role, invitation.isAllBranches(), invitation.branchUidList()));
          }
          invitation.accept(now, user.getUid());
          Map<String, Object> details = new java.util.LinkedHashMap<>();
          details.put("email", invitation.getEmail());
          details.put("acceptedAs", user.getEmail());
          details.put("role", role.getCode());
          audit.record("INVITATION_ACCEPTED", "User", user.getUid(), null, details);
          return null;
        });
  }

  /** Lets this transaction see the one invitation with {@code hash}. */
  private void nameToken(String hash) {
    jdbc.queryForList("select set_config(?, ?, true)", TOKEN_SETTING, hash);
  }

  private Role role(UUID roleUid) {
    Checks.required(roleUid, "roleUid");
    return roles
        .findByUid(roleUid)
        .filter(role -> role.getTenantUid().equals(TenantContext.requireTenant()))
        .orElseThrow(() -> Refusal.invalid("role_not_found", "No such role.", "roleUid"));
  }

  private static Set<UUID> branches(boolean allBranches, List<UUID> branchUids) {
    if (allBranches) {
      return Set.of();
    }
    if (branchUids == null || branchUids.isEmpty()) {
      throw Refusal.invalid(
          "invalid_branch_uids", "Name at least one branch, or allow every branch.", "branchUids");
    }
    return new LinkedHashSet<>(branchUids);
  }

  private InvitationView view(Invitation invitation, Instant at) {
    String acceptedAs =
        invitation.getAcceptedByUserUid() == null
            ? null
            : users.findByUid(invitation.getAcceptedByUserUid()).map(User::getEmail).orElse(null);
    return new InvitationView(
        invitation.getUid(),
        invitation.getEmail(),
        invitation.getName(),
        invitation.getRole().getUid(),
        invitation.getRole().getName(),
        invitation.isAllBranches(),
        invitation.branchUidList(),
        invitation.status(at),
        invitation.getInvitedBy(),
        invitation.getExpiresAt(),
        invitation.getCreatedAt(),
        acceptedAs);
  }

  private static Refusal notFound() {
    return Refusal.notFound("invitation_not_found", "No such invitation.");
  }
}
