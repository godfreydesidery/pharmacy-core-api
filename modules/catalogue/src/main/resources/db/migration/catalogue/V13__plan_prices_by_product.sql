-- PERF-11: one medicine's prices in every plan (its plan prices, a plan price change) are read by
-- product. The checkout's read, by plan and product, uses price_list_items_unique. Built
-- CONCURRENTLY outside a transaction (V13__plan_prices_by_product.sql.conf); if it stops half way,
-- see the runbook "Migrations".
create index concurrently if not exists price_list_items_by_tenant_product
  on catalogue.price_list_items (tenant_uid, product_uid);
