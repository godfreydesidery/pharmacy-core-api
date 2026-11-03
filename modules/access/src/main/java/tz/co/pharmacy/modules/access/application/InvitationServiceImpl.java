package tz.co.pharmacy.modules.access.application;

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
import tz.co.pharmacy.platform.kernel.Refusal;
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
  private final AuthenticationServiceImpl authentication;
  private final TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;
  private final String webUrl;

  InvitationServiceImpl(
      InvitationRepository invitations,
      RoleRepository roles,
      UserRepository users,
      MembershipRepository memberships,
      Passwords passwords,
      SessionIssuer issuer,
      AuthenticationServiceImpl authentication,
      TenantSwitch tenantSwitch,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.web-url:http://localhost:4300}") String webUrl) {
    this.invitations = invitations;
    this.roles = roles;
    this.users = users;
    this.memberships = memberships;
    this.passwords = passwords;
    this.issuer = issuer;
    this.authentication = authentication;
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
    Instant now = Instant.now();
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
  public List<InvitationView> list() {
    Instant now = Instant.now();
    return invitations.findTop100ByTenantUidOrderByIdDesc(TenantContext.requireTenant()).stream()
        .map(invitation -> view(invitation, now))
        .toList();
  }

  @Override
  @Transactional
  public void revoke(UUID invitationUid) {
    invitations
        .findByUidAndTenantUid(invitationUid, TenantContext.requireTenant())
        .orElseThrow(() -> Refusal.notFound("invitation_not_found", "No such invitation."))
        .revoke(Instant.now());
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
                  invitation.status(Instant.now()),
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
    Instant now = Instant.now();
    User user =
        ExecutionContext.callAs(
            SessionIssuer.serviceFrame(),
            () ->
                writes.execute(
                    status -> {
                      nameToken(hash);
                      Invitation invitation =
                          invitations
                              .findByTokenHash(hash)
                              .orElseThrow(InvitationServiceImpl::notFound);
                      if (!invitation.openAt(now)) {
                        throw Refusal.invalid(
                            "invitation_not_open",
                            "This invitation was already used, revoked or has expired.",
                            "token");
                      }
                      Optional<User> existing = users.findByEmail(invitation.getEmail());
                      User member;
                      if (existing.isPresent()) {
                        member = existing.get();
                        if (!member.isActive()
                            || !passwords.matches(password, member.getPasswordHash())) {
                          throw Refusal.unauthenticated(
                              "invalid_credentials",
                              "Enter the current password of " + invitation.getEmail() + ".");
                        }
                      } else {
                        member =
                            users.save(
                                new User(
                                    invitation.getEmail(),
                                    name == null || name.isBlank()
                                        ? invitation.getName()
                                        : Checks.text(name, "name", 120),
                                    passwords.hash(password, "password"),
                                    false));
                      }
                      User joining = member;
                      Role role = invitation.getRole();
                      tenantSwitch.within(
                          invitation.getTenantUid(),
                          () -> {
                            Optional<Membership> current =
                                memberships.findByUserUidAndTenantUid(
                                    joining.getUid(), invitation.getTenantUid());
                            if (current.isPresent()) {
                              current
                                  .get()
                                  .change(
                                      role,
                                      invitation.isAllBranches(),
                                      invitation.branchUidList(),
                                      true);
                            } else {
                              memberships.save(
                                  new Membership(
                                      joining.getUid(),
                                      role,
                                      invitation.isAllBranches(),
                                      invitation.branchUidList()));
                            }
                            invitation.accept(now);
                            audit.record(
                                "INVITATION_ACCEPTED",
                                "User",
                                joining.getUid(),
                                null,
                                Map.of("email", invitation.getEmail(), "role", role.getCode()));
                            return null;
                          });
                      return joining;
                    }));
    if (user == null) {
      throw notFound();
    }
    if (user.isTwoFactorEnabled()) {
      return authentication.signIn(user.getEmail(), password, client);
    }
    return new SignInOutcome.Signed(issuer.issue(user, client, "INVITATION"));
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

  private static InvitationView view(Invitation invitation, Instant at) {
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
        invitation.getCreatedAt());
  }

  private static Refusal notFound() {
    return Refusal.notFound("invitation_not_found", "No such invitation.");
  }
}
