-- Product types and tracking modes: a pharmacy also sells medical equipment (wheelchairs, BP
-- monitors, nebulisers), medical supplies, cosmetics, supplements and baby care. Medicines keep
-- their clinical fields; other products have no dosage form. Categories form a tree.

alter table catalogue.products
  add column product_type text not null default 'MEDICINE'
    check (product_type in ('MEDICINE', 'MEDICAL_EQUIPMENT', 'MEDICAL_SUPPLY', 'COSMETIC',
                            'SUPPLEMENT', 'BABY_CARE', 'OTHER')),
  add column tracking_mode text not null default 'BATCH_EXPIRY'
    check (tracking_mode in ('BATCH_EXPIRY', 'BATCH', 'SERIAL', 'QUANTITY')),
  add column unit_of_measure text not null default 'PIECE'
    check (unit_of_measure in ('PIECE', 'BOX', 'PACK', 'STRIP', 'BOTTLE', 'TUBE', 'SACHET', 'VIAL',
                               'PAIR', 'SET', 'KIT', 'ROLL')),
  add column brand text,
  add column model text,
  add column warranty_months integer check (warranty_months between 0 and 120),
  add column requires_serial_at_sale boolean not null default false;

alter table catalogue.products alter column dosage_form drop not null;

-- A medicine always has a dosage form; nothing else does.
alter table catalogue.products
  add constraint products_dosage_form_by_type
    check ((product_type = 'MEDICINE') = (dosage_form is not null));

create index products_by_type on catalogue.products (tenant_uid, product_type);

alter table catalogue.categories
  add column parent_id bigint references catalogue.categories (id);
create index categories_by_parent on catalogue.categories (parent_id);

-- A name is unique among its siblings, not across the whole tree.
drop index catalogue.categories_unique_name;
create unique index categories_unique_name
  on catalogue.categories (tenant_uid, coalesce(parent_id, 0), lower(name));
