-- A promotion may run with no end date (until it is switched off); a null valid_to means open-ended.
alter table catalogue.promotions alter column valid_to drop not null;
