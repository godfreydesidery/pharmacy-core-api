package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.application.checkout.ports.inbound.Checkout;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutCommand;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.application.onboarding.ports.inbound.OnboardTenantCommand;
import tz.co.pharmacy.application.onboarding.ports.inbound.TenantOnboarding;
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.access.ports.inbound.RoleView;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CategoryView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.DosageForm;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ManufacturerCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductType;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Storage;
import tz.co.pharmacy.modules.catalogue.ports.inbound.TrackingMode;
import tz.co.pharmacy.modules.catalogue.ports.inbound.UnitOfMeasure;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.RegisterTenantCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The demo tenant of a local run ({@code pharmacy.seed.demo=true}): Afya Pharmacy with a head
 * office and a second branch, an administrator and a cashier, a catalogue, suppliers and customers,
 * stock in several batches (some near expiry, some low) and a few sales. It runs once: it does
 * nothing when the AFYA tenant exists. Every write goes through the modules' inbound ports, as a
 * platform service, exactly as a request would.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.seed.demo", havingValue = "true")
@Order(20)
class DevelopmentSeed {

  private static final Logger log = LoggerFactory.getLogger(DevelopmentSeed.class);
  private static final String SERVICE = "demo-seed";

  private final TenantService tenants;
  private final TenantOnboarding onboarding;
  private final BranchService branches;
  private final UserAdministration users;
  private final CatalogueService catalogue;
  private final SupplierService suppliers;
  private final CustomerService customers;
  private final Receiving receiving;
  private final Checkout checkout;

  private final tz.co.pharmacy.modules.insurance.ports.inbound.Insurance insurance;

  private final tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit credit;

  DevelopmentSeed(
      TenantService tenants,
      TenantOnboarding onboarding,
      BranchService branches,
      UserAdministration users,
      CatalogueService catalogue,
      SupplierService suppliers,
      CustomerService customers,
      Receiving receiving,
      Checkout checkout,
      tz.co.pharmacy.modules.insurance.ports.inbound.Insurance insurance,
      tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit credit) {
    this.tenants = tenants;
    this.onboarding = onboarding;
    this.branches = branches;
    this.users = users;
    this.catalogue = catalogue;
    this.suppliers = suppliers;
    this.customers = customers;
    this.receiving = receiving;
    this.checkout = checkout;
    this.insurance = insurance;
    this.credit = credit;
  }

  @EventListener(ApplicationReadyEvent.class)
  void seed() {
    boolean exists =
        platform(
            () ->
                tenants.list("AFYA", PageQuery.first(5)).items().stream()
                    .anyMatch(tenant -> tenant.code().equals("AFYA")));
    if (exists) {
      return;
    }
    TenantView afya =
        platform(
            () ->
                onboarding.onboard(
                    new OnboardTenantCommand(
                        new RegisterTenantCommand(
                            "AFYA",
                            "Afya Pharmacy",
                            "Afya Pharmacy Ltd",
                            "TZ",
                            "TZS",
                            "Africa/Dar_es_Salaam",
                            new BranchCommand(
                                "HQ",
                                "Head Office",
                                "Plot 12, Samora Avenue, Dar es Salaam",
                                "+255 222 000 111",
                                "PC/DSM/2026/0451")),
                        new NewUserCommand("Amina Hassan", "admin@afya.co.tz", "Afya#2026"))));
    UUID tenant = afya.uid();
    BranchView hq = inTenant(tenant, null, () -> branches.list().getFirst());
    BranchView mbezi =
        inTenant(
            tenant,
            null,
            () ->
                branches.create(
                    new BranchCommand(
                        "MBZ",
                        "Mbezi Branch",
                        "Mbezi Beach, Dar es Salaam",
                        "+255 222 000 222",
                        "PC/DSM/2026/0452")));

    inTenant(
        tenant,
        null,
        () -> {
          RoleView cashier =
              users.roles().stream()
                  .filter(r -> r.code().equals("CASHIER"))
                  .findFirst()
                  .orElseThrow();
          users.addMember(
              new AddMemberCommand(
                  "Ali Salim",
                  "cashier@afya.co.tz",
                  "Cashier#2026",
                  cashier.uid(),
                  false,
                  List.of(hq.uid())));
          return null;
        });

    Map<String, UUID> categories = new java.util.LinkedHashMap<>();
    UUID gsk;
    UUID cipla;
    UUID local;
    List<UUID> products = new ArrayList<>();
    List<Integer> costs = new ArrayList<>();
    gsk =
        inTenant(
            tenant,
            null,
            () -> catalogue.createManufacturer(new ManufacturerCommand("GSK", "GB")).uid());
    cipla =
        inTenant(
            tenant,
            null,
            () -> catalogue.createManufacturer(new ManufacturerCommand("Cipla", "IN")).uid());
    local =
        inTenant(
            tenant,
            null,
            () ->
                catalogue
                    .createManufacturer(new ManufacturerCommand("Shelys Pharmaceuticals", "TZ"))
                    .uid());
    // The first read of the categories seeds the default tree; the demo files its products in it.
    Map<String, UUID> tree =
        inTenant(
            tenant,
            null,
            () ->
                catalogue.categories().stream()
                    .collect(Collectors.toMap(CategoryView::name, CategoryView::uid, (a, b) -> a)));
    Map<String, String> treeNames =
        Map.of(
            "Analgesic", "Analgesics",
            "Antibiotic", "Antibiotics",
            "Antimalarial", "Antimalarials",
            "Antihistamine", "Antihistamines",
            "Antidiabetic", "Antidiabetics",
            "Cardiovascular", "Cardiovascular",
            "Respiratory", "Respiratory",
            "Supplement", "Supplements & Nutrition");
    treeNames.forEach((demo, inTree) -> categories.put(demo, tree.get(inTree)));
    Object[][] catalogueRows = {
      {
        "Paracetamol 500mg",
        "Paracetamol",
        "PCM500",
        "Analgesic",
        DosageForm.TABLET,
        "500 mg",
        Schedule.OTC,
        1500,
        800,
        200,
        gsk
      },
      {
        "Ibuprofen 400mg",
        "Ibuprofen",
        "IBU400",
        "Analgesic",
        DosageForm.TABLET,
        "400 mg",
        Schedule.OTC,
        1400,
        700,
        150,
        local
      },
      {
        "Amoxicillin 250mg",
        "Amoxicillin",
        "AMX250",
        "Antibiotic",
        DosageForm.CAPSULE,
        "250 mg",
        Schedule.PRESCRIPTION,
        3800,
        2100,
        100,
        cipla
      },
      {
        "Azithromycin 500mg",
        "Azithromycin",
        "AZI500",
        "Antibiotic",
        DosageForm.TABLET,
        "500 mg",
        Schedule.PRESCRIPTION,
        7900,
        4500,
        60,
        cipla
      },
      {
        "Ciprofloxacin 500mg",
        "Ciprofloxacin",
        "CIP500",
        "Antibiotic",
        DosageForm.TABLET,
        "500 mg",
        Schedule.PRESCRIPTION,
        3400,
        1800,
        80,
        local
      },
      {
        "Artemether/Lumefantrine 20/120",
        "Artemether/Lumefantrine",
        "ALU20",
        "Antimalarial",
        DosageForm.TABLET,
        "20/120 mg",
        Schedule.OTC,
        5500,
        3200,
        100,
        local
      },
      {
        "Cetirizine 10mg",
        "Cetirizine",
        "CET10",
        "Antihistamine",
        DosageForm.TABLET,
        "10 mg",
        Schedule.OTC,
        1800,
        900,
        100,
        cipla
      },
      {
        "Metformin 500mg",
        "Metformin",
        "MET500",
        "Antidiabetic",
        DosageForm.TABLET,
        "500 mg",
        Schedule.PRESCRIPTION,
        1200,
        600,
        150,
        local
      },
      {
        "Amlodipine 5mg",
        "Amlodipine",
        "AML5",
        "Cardiovascular",
        DosageForm.TABLET,
        "5 mg",
        Schedule.PRESCRIPTION,
        2100,
        950,
        80,
        cipla
      },
      {
        "Salbutamol Inhaler 100mcg",
        "Salbutamol",
        "SAL100",
        "Respiratory",
        DosageForm.INHALER,
        "100 mcg",
        Schedule.PRESCRIPTION,
        8500,
        4800,
        20,
        gsk
      },
      {
        "Cough Syrup 100ml",
        "Dextromethorphan",
        "CSY100",
        "Respiratory",
        DosageForm.SYRUP,
        "100 ml",
        Schedule.OTC,
        4500,
        2200,
        40,
        local
      },
      {
        "Vitamin C 1000mg",
        "Ascorbic Acid",
        "VTC1000",
        "Supplement",
        DosageForm.TABLET,
        "1000 mg",
        Schedule.OTC,
        900,
        400,
        100,
        local
      },
      {
        "ORS Sachet",
        "Oral Rehydration Salts",
        "ORS1",
        "Supplement",
        DosageForm.SACHET,
        "20.5 g",
        Schedule.OTC,
        600,
        250,
        200,
        local
      },
      {
        "Folic Acid 5mg",
        "Folic Acid",
        "FOL5",
        "Supplement",
        DosageForm.TABLET,
        "5 mg",
        Schedule.OTC,
        500,
        200,
        100,
        local
      },
    };
    for (Object[] row : catalogueRows) {
      products.add(
          inTenant(
              tenant,
              null,
              () ->
                  catalogue
                      .createProduct(
                          new ProductCommand(
                              (String) row[0],
                              (String) row[1],
                              (String) row[2],
                              null,
                              null,
                              categories.get((String) row[3]),
                              (UUID) row[10],
                              (DosageForm) row[4],
                              (String) row[5],
                              null,
                              (Schedule) row[6],
                              BigDecimal.valueOf((Integer) row[7]),
                              BigDecimal.valueOf((Integer) row[8]),
                              BigDecimal.ZERO,
                              (Integer) row[9],
                              Storage.ROOM_TEMPERATURE,
                              "Supplement".equals(row[3])
                                  ? ProductType.SUPPLEMENT
                                  : ProductType.MEDICINE,
                              null,
                              unitOf((DosageForm) row[4]),
                              null,
                              null,
                              null,
                              null))
                      .uid()));
      costs.add((Integer) row[8]);
    }

    // The equipment and supplies range: wheelchairs and BP monitors are tracked by serial number,
    // crutches by quantity, gloves by batch without expiry.
    UUID wheelchair =
        equipment(
            tenant,
            "Wheelchair Standard 18\"",
            "WCH18",
            tree.get("Mobility Aids"),
            ProductType.MEDICAL_EQUIPMENT,
            TrackingMode.SERIAL,
            UnitOfMeasure.PIECE,
            "Invacare",
            "Action 1",
            12,
            450_000,
            320_000,
            1);
    UUID bpMonitor =
        equipment(
            tenant,
            "Digital BP Monitor",
            "BPM-M3",
            tree.get("Diagnostic Devices"),
            ProductType.MEDICAL_EQUIPMENT,
            TrackingMode.SERIAL,
            UnitOfMeasure.PIECE,
            "Omron",
            "M3 Comfort",
            24,
            95_000,
            60_000,
            2);
    UUID crutches =
        equipment(
            tenant,
            "Crutches (Pair)",
            "CRT-PR",
            tree.get("Mobility Aids"),
            ProductType.MEDICAL_EQUIPMENT,
            TrackingMode.QUANTITY,
            UnitOfMeasure.PAIR,
            null,
            null,
            6,
            45_000,
            28_000,
            2);
    UUID gloves =
        equipment(
            tenant,
            "Surgical Gloves Box (100)",
            "GLV-100",
            tree.get("Gloves"),
            ProductType.MEDICAL_SUPPLY,
            TrackingMode.BATCH,
            UnitOfMeasure.BOX,
            "SafeTouch",
            null,
            null,
            18_000,
            11_000,
            10);

    UUID medSupply =
        inTenant(
            tenant,
            null,
            () ->
                suppliers
                    .create(
                        new SupplierCommand(
                            "MedSupply Africa Ltd",
                            "Joseph Kimaro",
                            "+255 222 456 789",
                            "sales@medsupply.co.tz",
                            "100-200-300",
                            PaymentTerms.NET_30,
                            "Pugu Road, Dar es Salaam",
                            true))
                    .uid());
    inTenant(
        tenant,
        null,
        () ->
            suppliers.create(
                new SupplierCommand(
                    "Cipla Distributors",
                    "Anita Shah",
                    "+255 222 334 110",
                    "orders@cipla-dist.com",
                    null,
                    PaymentTerms.NET_45,
                    null,
                    true)));
    UUID clinic =
        inTenant(
            tenant,
            null,
            () ->
                customers
                    .create(
                        new CustomerCommand(
                            "City Clinic Ltd",
                            "+255 222 110 900",
                            "orders@cityclinic.co.tz",
                            CustomerType.WHOLESALE,
                            null,
                            null,
                            BigDecimal.valueOf(2_000_000),
                            "Kariakoo, Dar es Salaam",
                            true))
                    .uid());
    UUID neema =
        inTenant(
            tenant,
            null,
            () ->
                customers
                    .create(
                        new CustomerCommand(
                            "Neema Kileo",
                            "+255 754 222 019",
                            null,
                            CustomerType.INSURANCE,
                            "NHIF",
                            "NHIF-0099812",
                            null,
                            null,
                            true))
                    .uid());

    // Stock at both branches: most batches far from expiry, a few near it, one product low.
    LocalDate today = LocalDate.now(Clocks.clock());
    for (BranchView branch : List.of(hq, mbezi)) {
      List<ReceiptLineCommand> lines = new ArrayList<>();
      for (int i = 0; i < products.size(); i++) {
        int quantity = i == 9 ? 12 : 120 + 40 * (i % 4);
        lines.add(
            new ReceiptLineCommand(
                products.get(i),
                "B" + branch.code() + (100 + i),
                today.plusDays(i % 5 == 0 ? 45 + i : 420 + 10 * i),
                quantity,
                BigDecimal.valueOf(costs.get(i)),
                null));
      }
      if (branch.uid().equals(hq.uid())) {
        lines.add(
            new ReceiptLineCommand(
                wheelchair,
                null,
                null,
                3,
                BigDecimal.valueOf(320_000),
                List.of("WCH-0001", "WCH-0002", "WCH-0003")));
        lines.add(
            new ReceiptLineCommand(
                bpMonitor,
                null,
                null,
                4,
                BigDecimal.valueOf(60_000),
                List.of("BPM-1001", "BPM-1002", "BPM-1003", "BPM-1004")));
        lines.add(
            new ReceiptLineCommand(crutches, null, null, 10, BigDecimal.valueOf(28_000), null));
        lines.add(
            new ReceiptLineCommand(
                gloves, "GLV-2026A", null, 40, BigDecimal.valueOf(11_000), null));
      }
      inTenant(
          tenant,
          branch.uid(),
          () ->
              receiving.receive(
                  new RecordReceiptCommand(
                      null,
                      medSupply,
                      "SI-" + branch.code() + "-0001",
                      "Opening delivery",
                      lines,
                      null,
                      null)));
    }

    // A few sales at the head office.
    int[][] baskets = {{0, 3}, {1, 2}, {6, 1}, {10, 1}, {11, 6}, {12, 4}, {0, 2}, {5, 1}};
    for (int i = 0; i < baskets.length; i++) {
      int[] basket = baskets[i];
      UUID customer = i == 3 ? clinic : null;
      inTenant(
          tenant,
          hq.uid(),
          () ->
              checkout.checkout(
                  new CheckoutCommand(
                      UUID.randomUUID().toString(),
                      customer,
                      BigDecimal.ZERO,
                      List.of(new CheckoutLine(products.get(basket[0]), basket[1], null)),
                      List.of(
                          new PaymentCommand(
                              PaymentMethod.CASH, BigDecimal.valueOf(100_000), null)),
                      null)));
    }
    // A wheelchair sold by serial number, with its 12-month warranty on the receipt.
    inTenant(
        tenant,
        hq.uid(),
        () ->
            checkout.checkout(
                new CheckoutCommand(
                    UUID.randomUUID().toString(),
                    clinic,
                    BigDecimal.ZERO,
                    List.of(new CheckoutLine(wheelchair, 1, List.of("WCH-0001"))),
                    List.of(
                        new PaymentCommand(PaymentMethod.CASH, BigDecimal.valueOf(450_000), null)),
                    null)));
    // Insurance: NHIF pays 90% for Neema Kileo (10% co-pay); cosmetics are not covered.
    UUID neemaMember =
        inTenant(
            tenant,
            null,
            () -> {
              var nhif =
                  insurance.createPayer(
                      new tz.co.pharmacy.modules.insurance.ports.inbound.PayerCommand(
                          "NHIF",
                          "National Health Insurance Fund",
                          "Claims Office",
                          "claims@nhif.go.tz",
                          30,
                          false,
                          true));
              var scheme =
                  insurance.createScheme(
                      new tz.co.pharmacy.modules.insurance.ports.inbound.SchemeCommand(
                          nhif.uid(),
                          "NHIF Standard",
                          tz.co.pharmacy.modules.insurance.ports.inbound.CopayType.PERCENT,
                          BigDecimal.TEN,
                          BigDecimal.valueOf(1_500_000),
                          java.util.Set.of("COSMETIC"),
                          java.util.Set.of(),
                          true));
              return insurance
                  .enrol(
                      new tz.co.pharmacy.modules.insurance.ports.inbound.MemberCommand(
                          neema, scheme.uid(), "NHIF-0099812", null, null, null, true))
                  .uid();
            });
    // An insured sale: the claim is raised for NHIF's share, Neema pays the co-pay in cash.
    inTenant(
        tenant,
        hq.uid(),
        () ->
            checkout.checkout(
                new CheckoutCommand(
                    UUID.randomUUID().toString(),
                    neema,
                    BigDecimal.ZERO,
                    List.of(
                        new CheckoutLine(products.get(5), 2, null),
                        new CheckoutLine(products.get(6), 1, null)),
                    List.of(
                        new PaymentCommand(PaymentMethod.CASH, BigDecimal.valueOf(2_000), null)),
                    null,
                    neemaMember,
                    null,
                    "B54",
                    null,
                    null)));
    // A credit sale: City Clinic takes 20 strips on account, then pays part by mobile money.
    inTenant(
        tenant,
        hq.uid(),
        () ->
            checkout.checkout(
                new CheckoutCommand(
                    UUID.randomUUID().toString(),
                    clinic,
                    BigDecimal.ZERO,
                    List.of(new CheckoutLine(products.get(0), 20, null)),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    true,
                    null)));
    inTenant(
        tenant,
        null,
        () ->
            credit.receivePayment(
                new tz.co.pharmacy.modules.receivables.ports.inbound.ReceivePaymentCommand(
                    clinic,
                    BigDecimal.valueOf(10_000),
                    "MOBILE_MONEY",
                    "MPESA-QX7Y2",
                    null,
                    List.of(),
                    null,
                    null)));
    log.info(
        "Demo tenant seeded: Afya Pharmacy (AFYA), branches HQ and MBZ;"
            + " admin@afya.co.tz / Afya#2026, cashier@afya.co.tz / Cashier#2026");
  }

  /** A product that is not a medicine: no dosage form, sold over the counter. */
  private UUID equipment(
      UUID tenant,
      String name,
      String sku,
      UUID category,
      ProductType type,
      TrackingMode tracking,
      UnitOfMeasure unit,
      String brand,
      String model,
      Integer warrantyMonths,
      int price,
      int cost,
      int reorderLevel) {
    return inTenant(
        tenant,
        null,
        () ->
            catalogue
                .createProduct(
                    new ProductCommand(
                        name,
                        null,
                        sku,
                        null,
                        null,
                        category,
                        null,
                        null,
                        null,
                        null,
                        Schedule.OTC,
                        BigDecimal.valueOf(price),
                        BigDecimal.valueOf(cost),
                        BigDecimal.ZERO,
                        reorderLevel,
                        Storage.ROOM_TEMPERATURE,
                        type,
                        tracking,
                        unit,
                        brand,
                        model,
                        warrantyMonths,
                        null))
                .uid());
  }

  /** The unit a medicine of this dosage form is sold in. */
  private static UnitOfMeasure unitOf(DosageForm form) {
    return switch (form) {
      case TABLET, CAPSULE -> UnitOfMeasure.STRIP;
      case SYRUP, SUSPENSION, DROPS -> UnitOfMeasure.BOTTLE;
      case CREAM, OINTMENT, GEL -> UnitOfMeasure.TUBE;
      case SACHET -> UnitOfMeasure.SACHET;
      case INJECTION -> UnitOfMeasure.VIAL;
      default -> UnitOfMeasure.PIECE;
    };
  }

  private <T> T platform(Supplier<T> work) {
    return ExecutionContext.callAs(ExecutionContext.Frame.service(SERVICE, null, null), work);
  }

  private <T> T inTenant(UUID tenant, UUID branch, Supplier<T> work) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, tenant, branch),
        () -> TenantContext.callAs(tenant, work));
  }
}
