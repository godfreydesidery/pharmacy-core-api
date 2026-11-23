-- FRD-8: a promotion is never deleted, only switched off, so the sales it priced keep naming it
-- (sales.sale_lines.promotion_uid). The application role loses the right to delete one; erasing a
-- whole pharmacy runs as the owner and is not affected.
revoke delete on catalogue.promotions from ${application_role};
