package tz.co.pharmacy.modules.tenancy.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.tenancy.domain.Branch;
import tz.co.pharmacy.modules.tenancy.persistence.BranchRepository;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
class BranchServiceImpl implements BranchService {

  private final BranchRepository branches;

  BranchServiceImpl(BranchRepository branches) {
    this.branches = branches;
  }

  @Override
  @Transactional(readOnly = true)
  public List<BranchView> list() {
    return branches.findAllByOrderByHeadOfficeDescNameAsc().stream()
        .map(BranchServiceImpl::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BranchView> find(UUID branchUid) {
    if (branchUid == null) {
      return Optional.empty();
    }
    return branches.findByUid(branchUid).map(BranchServiceImpl::view);
  }

  @Override
  @Transactional
  public BranchView create(BranchCommand command) {
    Checks.required(command, "branch");
    String code = Checks.code(command.code(), "code");
    if (branches.existsByCode(code)) {
      throw Refusal.conflict("duplicate_code", "A branch with code " + code + " exists.");
    }
    Branch branch =
        branches.save(
            new Branch(
                code,
                Checks.text(command.name(), "name", 120),
                Checks.optionalText(command.address(), "address", 300),
                Checks.optionalText(command.phone(), "phone", 40),
                Checks.optionalText(command.licenceNumber(), "licenceNumber", 60),
                false));
    return view(branch);
  }

  @Override
  @Transactional
  public BranchView change(UUID branchUid, BranchCommand command, boolean active) {
    Checks.required(command, "branch");
    Branch branch =
        branches
            .findByUid(branchUid)
            .orElseThrow(() -> Refusal.notFound("branch_not_found", "No such branch."));
    String code = Checks.code(command.code(), "code");
    if (!code.equals(branch.getCode()) && branches.existsByCode(code)) {
      throw Refusal.conflict("duplicate_code", "A branch with code " + code + " exists.");
    }
    if (branch.isHeadOffice() && !active) {
      throw Refusal.invalid(
          "head_office_required", "The head office cannot be deactivated.", "active");
    }
    branch.change(
        code,
        Checks.text(command.name(), "name", 120),
        Checks.optionalText(command.address(), "address", 300),
        Checks.optionalText(command.phone(), "phone", 40),
        Checks.optionalText(command.licenceNumber(), "licenceNumber", 60),
        active);
    return view(branch);
  }

  static BranchView view(Branch branch) {
    return new BranchView(
        branch.getUid(),
        branch.getCode(),
        branch.getName(),
        branch.getAddress(),
        branch.getPhone(),
        branch.getLicenceNumber(),
        branch.isHeadOffice(),
        branch.isActive(),
        branch.getCreatedAt());
  }
}
