package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.util.List;

/**
 * The payer's decision: approved amount and rejection reason per line, and where the rejected part
 * goes.
 */
public record AdjudicationCommand(
    ClaimStatus status, List<ClaimLineView> lines, String note, RejectedTo rejectedTo) {}
