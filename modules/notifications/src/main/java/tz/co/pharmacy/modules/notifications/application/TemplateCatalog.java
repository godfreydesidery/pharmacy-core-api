package tz.co.pharmacy.modules.notifications.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;

/**
 * The built-in templates, in English and Swahili, for every key. Each has a subject, a long body
 * (email, and the in-app body when no short text fits) and a short text (SMS, WhatsApp and in-app).
 */
final class TemplateCatalog {

  static final List<String> LOCALES = List.of("en", "sw");

  record Default(String subject, String body, String text) {}

  private static final Map<String, Map<String, Default>> DEFAULTS = new LinkedHashMap<>();
  private static final Map<String, Map<String, Object>> SAMPLES = new LinkedHashMap<>();

  static {
    define(
        TemplateKeys.STOCK_LOW,
        new Default(
            "Low stock: {{productName}}",
            "{{productName}} is down to {{quantity}} at {{branchName}}, at or below its reorder level of"
                + " {{reorderLevel}}. Reorder soon so patients are not turned away.",
            "{{productName}} is low at {{branchName}}: {{quantity}} left (reorder level {{reorderLevel}})."),
        new Default(
            "Bidhaa inakaribia kuisha: {{productName}}",
            "{{productName}} imebaki {{quantity}} katika {{branchName}}, chini ya kiwango cha kuagiza"
                + " ({{reorderLevel}}). Agiza mapema ili wagonjwa wasikose dawa.",
            "{{productName}} inakaribia kuisha {{branchName}}: zimebaki {{quantity}} (kiwango"
                + " {{reorderLevel}})."),
        Map.of(
            "productName", "Paracetamol 500mg",
            "quantity", 18,
            "reorderLevel", 200,
            "branchName", "Head Office",
            "link", "/inventory/stock"));
    define(
        TemplateKeys.STOCK_EXPIRING,
        new Default(
            "{{count}} batches expire within {{days}} days",
            "These batches expire within {{days}} days:\n{{items}}\nDiscount them, transfer them to a"
                + " busier branch or return them to the supplier before they expire.",
            "{{count}} batches expire within {{days}} days. Open Expiry to act on them."),
        new Default(
            "Bechi {{count}} zinaisha muda ndani ya siku {{days}}",
            "Bechi hizi zinaisha muda ndani ya siku {{days}}:\n{{items}}\nPunguza bei, hamishia tawi"
                + " lenye mauzo zaidi au rudisha kwa msambazaji kabla hazijaisha muda.",
            "Bechi {{count}} zinaisha muda ndani ya siku {{days}}. Fungua Muda wa Kuisha."),
        Map.of(
            "count",
            3,
            "days",
            90,
            "items",
            "• Amoxicillin 250mg (AMX123) – 12 Dec 2026\n• Cetirizine 10mg (CET456) – 7 Jan 2027",
            "link",
            "/inventory/expiry"));
    define(
        TemplateKeys.SALE_RECEIPT,
        new Default(
            "Your receipt {{number}} from {{pharmacyName}}",
            "Thank you for shopping at {{pharmacyName}}. Receipt {{number}}, total {{total}}, on"
                + " {{date}}. Get well soon.",
            "{{pharmacyName}}: receipt {{number}}, total {{total}}. Thank you, get well soon."),
        new Default(
            "Risiti yako {{number}} kutoka {{pharmacyName}}",
            "Asante kwa kununua {{pharmacyName}}. Risiti {{number}}, jumla {{total}}, tarehe"
                + " {{date}}. Ugua pole.",
            "{{pharmacyName}}: risiti {{number}}, jumla {{total}}. Asante, ugua pole."),
        Map.of(
            "pharmacyName", "Afya Pharmacy",
            "number", "INV-HQ-000124",
            "total", "TZS 9,000.00",
            "date", "3 Nov 2026"));
    define(
        TemplateKeys.DAILY_SUMMARY,
        new Default(
            "{{pharmacyName}} today: {{sales}} in {{transactions}} sales",
            "Today {{pharmacyName}} sold {{sales}} in {{transactions}} transactions. Best seller:"
                + " {{topProduct}}.",
            "{{pharmacyName}} today: {{sales}}, {{transactions}} sales. Best seller: {{topProduct}}."),
        new Default(
            "{{pharmacyName}} leo: {{sales}} kwa mauzo {{transactions}}",
            "Leo {{pharmacyName}} imeuza {{sales}} kwa mauzo {{transactions}}. Inayouzwa zaidi:"
                + " {{topProduct}}.",
            "{{pharmacyName}} leo: {{sales}}, mauzo {{transactions}}. Inayouzwa zaidi: {{topProduct}}."),
        Map.of(
            "pharmacyName", "Afya Pharmacy",
            "sales", "TZS 452,500.00",
            "transactions", 64,
            "topProduct", "Paracetamol 500mg",
            "link", "/dashboard"));
    define(
        TemplateKeys.CUSTOMER_OVERDUE,
        new Default(
            "A reminder from {{pharmacyName}}",
            "Dear {{customerName}}, your balance of {{amount}} with {{pharmacyName}} is overdue."
                + " Please settle it at your convenience.",
            "Dear {{customerName}}, your balance of {{amount}} at {{pharmacyName}} is overdue."),
        new Default(
            "Ukumbusho kutoka {{pharmacyName}}",
            "Mpendwa {{customerName}}, deni lako la {{amount}} kwa {{pharmacyName}} limepita muda."
                + " Tafadhali lilipe kwa wakati unaokufaa.",
            "Mpendwa {{customerName}}, deni lako la {{amount}} kwa {{pharmacyName}} limepita muda."),
        Map.of(
            "customerName",
            "City Clinic Ltd",
            "amount",
            "TZS 240,000.00",
            "pharmacyName",
            "Afya Pharmacy"));
    define(
        TemplateKeys.ACCESS_INVITATION,
        new Default(
            "{{inviterName}} invited you to {{pharmacyName}}",
            "Hello {{name}}, {{inviterName}} invited you to work in {{pharmacyName}} on Pharmacy Core."
                + " Accept the invitation to set your password: {{link}}",
            "{{inviterName}} invited you to {{pharmacyName}}. Accept: {{link}}"),
        new Default(
            "{{inviterName}} amekualika {{pharmacyName}}",
            "Habari {{name}}, {{inviterName}} amekualika kufanya kazi {{pharmacyName}} kwenye Pharmacy"
                + " Core. Kubali mwaliko ili uweke nenosiri lako: {{link}}",
            "{{inviterName}} amekualika {{pharmacyName}}. Kubali: {{link}}"),
        Map.of(
            "name", "Ali Salim",
            "inviterName", "Amina Hassan",
            "pharmacyName", "Afya Pharmacy",
            "link", "http://localhost:4300/invitations/accept?token=…"));
    define(
        TemplateKeys.ACCESS_PASSWORD_RESET,
        new Default(
            "Reset your Pharmacy Core password",
            "Hello {{name}}, someone asked to reset your password. Use this link within {{minutes}}"
                + " minutes: {{link}}\nIf it was not you, ignore this message; your password is unchanged.",
            "Reset your Pharmacy Core password within {{minutes}} min: {{link}}"),
        new Default(
            "Badilisha nenosiri lako la Pharmacy Core",
            "Habari {{name}}, kuna aliyeomba kubadilisha nenosiri lako. Tumia kiungo hiki ndani ya dakika"
                + " {{minutes}}: {{link}}\nKama si wewe, puuza ujumbe huu; nenosiri lako halijabadilika.",
            "Badilisha nenosiri la Pharmacy Core ndani ya dakika {{minutes}}: {{link}}"),
        Map.of(
            "name", "Amina Hassan",
            "minutes", 30,
            "link", "http://localhost:4300/reset-password?token=…"));
    define(
        TemplateKeys.ACCESS_NEW_SIGN_IN,
        new Default(
            "New sign-in to your account",
            "Hello {{name}}, your account was signed in from {{device}} ({{ip}}) at {{time}}. If it"
                + " was not you, change your password and sign out of other sessions now.",
            "New sign-in from {{device}} ({{ip}}) at {{time}}. Not you? Change your password."),
        new Default(
            "Akaunti yako imeingiwa kutoka kifaa kipya",
            "Habari {{name}}, akaunti yako imeingiwa kutoka {{device}} ({{ip}}) saa {{time}}. Kama"
                + " si wewe, badilisha nenosiri na utoke kwenye vipindi vingine sasa.",
            "Kuingia kupya kutoka {{device}} ({{ip}}) saa {{time}}. Si wewe? Badilisha nenosiri."),
        Map.of(
            "name", "Amina Hassan",
            "device", "Chrome on Windows",
            "ip", "196.249.10.20",
            "time", "3 Nov 2026 08:15",
            "link", "/account"));
    define(
        TemplateKeys.ACCESS_TWO_FACTOR_CODE,
        new Default(
            "Your sign-in code",
            "Your Pharmacy Core sign-in code is {{code}}. It expires in {{minutes}} minutes. Never share"
                + " it with anyone.",
            "Pharmacy Core code: {{code}} (expires in {{minutes}} min). Never share it."),
        new Default(
            "Msimbo wako wa kuingia",
            "Msimbo wako wa kuingia Pharmacy Core ni {{code}}. Unaisha baada ya dakika {{minutes}}."
                + " Usimpe mtu yeyote.",
            "Msimbo wa Pharmacy Core: {{code}} (unaisha dakika {{minutes}}). Usimpe mtu."),
        Map.of("code", "482913", "minutes", 5));
    define(
        TemplateKeys.ACCESS_VERIFICATION_CODE,
        new Default(
            "Your verification code",
            "Your Pharmacy Core verification code is {{code}}. It expires in {{minutes}} minutes."
                + " Never share it with anyone, not even Pharmacy Core staff.",
            "Pharmacy Core code: {{code}} (expires in {{minutes}} min). Never share it."),
        new Default(
            "Msimbo wako wa uthibitisho",
            "Msimbo wako wa uthibitisho wa Pharmacy Core ni {{code}}. Unaisha baada ya dakika"
                + " {{minutes}}. Usimpe mtu yeyote, hata wafanyakazi wa Pharmacy Core.",
            "Msimbo wa Pharmacy Core: {{code}} (unaisha dakika {{minutes}}). Usimpe mtu."),
        Map.of("code", "482913", "minutes", 10));
    define(
        TemplateKeys.ACCESS_EMAIL_CONFIRM,
        new Default(
            "Confirm your new sign-in email",
            "Hello {{name}}, confirm that you want to sign in to Pharmacy Core with this address:"
                + " {{link}} The link works for {{minutes}} minutes. If you did not ask for this,"
                + " ignore this email; nothing changes.",
            "Confirm your new Pharmacy Core email: {{link}}"),
        new Default(
            "Thibitisha barua pepe yako mpya ya kuingia",
            "Habari {{name}}, thibitisha kwamba unataka kuingia Pharmacy Core kwa anwani hii:"
                + " {{link}} Kiungo kinafanya kazi kwa dakika {{minutes}}. Kama hukuomba hili,"
                + " puuza barua hii; hakuna kitakachobadilika.",
            "Thibitisha barua pepe yako mpya ya Pharmacy Core: {{link}}"),
        Map.of("name", "Amina Hassan", "link", "/confirm-email?token=...", "minutes", 60));
    define(
        TemplateKeys.ACCESS_EMAIL_IN_USE,
        new Default(
            "This email already has an account",
            "Someone asked to use this address for a Pharmacy Core account, but it already has"
                + " one. Nothing was changed. If it was you, sign in with this address instead, or"
                + " use Forgot password.",
            "This email already has a Pharmacy Core account; nothing was changed."),
        new Default(
            "Barua pepe hii tayari ina akaunti",
            "Mtu ameomba kutumia anwani hii kwa akaunti ya Pharmacy Core, lakini tayari ina"
                + " akaunti. Hakuna kilichobadilika. Kama ni wewe, ingia kwa anwani hii, au tumia"
                + " Umesahau nenosiri.",
            "Barua pepe hii tayari ina akaunti ya Pharmacy Core; hakuna kilichobadilika."),
        Map.of());
    define(
        TemplateKeys.ACCESS_EMAIL_CHANGED,
        new Default(
            "Your sign-in email was changed",
            "Hello {{name}}, your Pharmacy Core sign-in email was changed to {{newEmail}} on"
                + " {{time}}. If this was not you, contact your pharmacy or Pharmacy Core support"
                + " at once.",
            "Your Pharmacy Core sign-in email was changed to {{newEmail}}. Not you? Contact"
                + " support now."),
        new Default(
            "Barua pepe yako ya kuingia imebadilishwa",
            "Habari {{name}}, barua pepe yako ya kuingia Pharmacy Core imebadilishwa kuwa"
                + " {{newEmail}} tarehe {{time}}. Kama si wewe, wasiliana na famasia yako au msaada"
                + " wa Pharmacy Core mara moja.",
            "Barua pepe yako ya Pharmacy Core imebadilishwa kuwa {{newEmail}}. Si wewe? Wasiliana"
                + " na msaada sasa."),
        Map.of(
            "name", "Amina Hassan", "newEmail", "a***@example.com", "time", "22 Nov 2026 10:15"));
    define(
        TemplateKeys.ACCESS_PASSWORD_CHANGED,
        new Default(
            "Your password was changed",
            "Hello {{name}}, the password of your Pharmacy Core account was changed on {{time}}."
                + " If this was not you, reset it now with Forgot password and contact support.",
            "Your Pharmacy Core password was changed. Not you? Reset it now."),
        new Default(
            "Nenosiri lako limebadilishwa",
            "Habari {{name}}, nenosiri la akaunti yako ya Pharmacy Core limebadilishwa tarehe"
                + " {{time}}. Kama si wewe, libadilishe sasa kwa Umesahau nenosiri na uwasiliane"
                + " na msaada.",
            "Nenosiri lako la Pharmacy Core limebadilishwa. Si wewe? Libadilishe sasa."),
        Map.of("name", "Amina Hassan", "time", "22 Nov 2026 10:15"));
    define(
        TemplateKeys.ACCESS_PHONE_REMOVED,
        new Default(
            "Your phone number was removed",
            "Hello {{name}}, the phone number {{phone}} was verified by another Pharmacy Core"
                + " account, so it is no longer on yours. Phone numbers are sometimes given to new"
                + " owners. Add your current number on your account's security page.",
            "{{phone}} was verified by another account and removed from yours."),
        new Default(
            "Namba yako ya simu imeondolewa",
            "Habari {{name}}, namba ya simu {{phone}} imethibitishwa na akaunti nyingine ya"
                + " Pharmacy Core, kwa hiyo haipo tena kwenye yako. Namba za simu wakati mwingine"
                + " hupewa wamiliki wapya. Ongeza namba yako ya sasa kwenye ukurasa wa usalama.",
            "{{phone}} imethibitishwa na akaunti nyingine na kuondolewa kwenye yako."),
        Map.of("name", "Amina Hassan", "phone", "+255754000111"));
    define(
        TemplateKeys.ACCESS_RECOVERY_STARTED,
        new Default(
            "Recovery of your account was started",
            "Hello {{name}}, Pharmacy Core support started a recovery of your account to the"
                + " address {{newEmail}}. It can be used from {{usableAt}}. If you did not ask for"
                + " this, cancel it now: {{cancelLink}}",
            "Recovery of your Pharmacy Core account to {{newEmail}} starts {{usableAt}}. Not you?"
                + " Cancel: {{cancelLink}}"),
        new Default(
            "Urejeshaji wa akaunti yako umeanzishwa",
            "Habari {{name}}, msaada wa Pharmacy Core umeanzisha urejeshaji wa akaunti yako kwenda"
                + " anwani {{newEmail}}. Unaweza kutumika kuanzia {{usableAt}}. Kama hukuomba hili,"
                + " usitishe sasa: {{cancelLink}}",
            "Urejeshaji wa akaunti yako ya Pharmacy Core kwenda {{newEmail}} unaanza {{usableAt}}."
                + " Si wewe? Sitisha: {{cancelLink}}"),
        Map.of(
            "name", "Amina Hassan",
            "newEmail", "a***@example.com",
            "usableAt", "23 Nov 2026 10:15",
            "cancelLink", "/cancel-recovery?token=..."));
    define(
        TemplateKeys.ACCESS_RECOVERY_LINK,
        new Default(
            "Recover your Pharmacy Core account",
            "Hello {{name}}, use this link to choose a new password and sign in with this address"
                + " from {{usableAt}}: {{link}} Your old email and phone were told, and the"
                + " recovery stops if they cancel it.",
            "Recover your Pharmacy Core account from {{usableAt}}: {{link}}"),
        new Default(
            "Rejesha akaunti yako ya Pharmacy Core",
            "Habari {{name}}, tumia kiungo hiki kuchagua nenosiri jipya na kuingia kwa anwani hii"
                + " kuanzia {{usableAt}}: {{link}} Barua pepe na simu yako ya zamani zimearifiwa,"
                + " na urejeshaji unasimama wakiusitisha.",
            "Rejesha akaunti yako ya Pharmacy Core kuanzia {{usableAt}}: {{link}}"),
        Map.of(
            "name", "Amina Hassan", "usableAt", "23 Nov 2026 10:15", "link", "/recover?token=..."));
    define(
        TemplateKeys.ACCESS_LOGIN_LINKED,
        new Default(
            "A staff login was linked to your account",
            "Hello {{name}}, the pharmacy staff login {{login}} was linked to your Pharmacy Core"
                + " account on {{time}}. From now on you sign in to that pharmacy with your own"
                + " account; the staff login no longer works. If this was not you, change your"
                + " password and contact support at once.",
            "Staff login {{login}} is now part of your Pharmacy Core account. Not you? Contact"
                + " support."),
        new Default(
            "Akaunti ya mfanyakazi imeunganishwa na akaunti yako",
            "Habari {{name}}, akaunti ya mfanyakazi {{login}} imeunganishwa na akaunti yako ya"
                + " Pharmacy Core tarehe {{time}}. Kuanzia sasa unaingia kwenye famasia hiyo kwa"
                + " akaunti yako; akaunti ya mfanyakazi haifanyi kazi tena. Kama si wewe, badilisha"
                + " nenosiri na uwasiliane na msaada mara moja.",
            "Akaunti ya mfanyakazi {{login}} sasa ni sehemu ya akaunti yako ya Pharmacy Core. Si"
                + " wewe? Wasiliana na msaada."),
        Map.of("name", "Juma Ali", "login", "juma@dawa", "time", "22 Nov 2026 10:15"));
    define(
        TemplateKeys.TENANT_WELCOME,
        new Default(
            "Welcome to Pharmacy Core, {{pharmacyName}}",
            "Hello {{name}}, {{pharmacyName}} is ready. Sign in to add your products, receive your first"
                + " stock and invite your team: {{link}}",
            "Welcome {{name}}! {{pharmacyName}} is ready on Pharmacy Core."),
        new Default(
            "Karibu Pharmacy Core, {{pharmacyName}}",
            "Habari {{name}}, {{pharmacyName}} iko tayari. Ingia uongeze bidhaa, upokee mzigo wa kwanza na"
                + " ualike timu yako: {{link}}",
            "Karibu {{name}}! {{pharmacyName}} iko tayari kwenye Pharmacy Core."),
        Map.of(
            "name",
            "Amina Hassan",
            "pharmacyName",
            "Afya Pharmacy",
            "link",
            "http://localhost:4300"));
    define(
        TemplateKeys.SUBSCRIPTION_DUE,
        new Default(
            "{{pharmacyName}}: subscription invoice {{number}} due {{dueDate}}",
            "Hello {{name}}, invoice {{number}} for the {{planName}} plan, {{amount}}, is due on"
                + " {{dueDate}}. Pay it on time to keep {{pharmacyName}} running without interruption.",
            "{{pharmacyName}}: Pharmacy Core invoice {{number}} ({{amount}}) is due {{dueDate}}."),
        new Default(
            "{{pharmacyName}}: ankara ya usajili {{number}} inadaiwa {{dueDate}}",
            "Habari {{name}}, ankara {{number}} ya mpango wa {{planName}}, {{amount}}, inapaswa"
                + " kulipwa tarehe {{dueDate}}. Lipa kwa wakati ili {{pharmacyName}} iendelee bila"
                + " kukatizwa.",
            "{{pharmacyName}}: ankara ya Pharmacy Core {{number}} ({{amount}}) inadaiwa {{dueDate}}."),
        Map.of(
            "name", "Amina Hassan",
            "pharmacyName", "Afya Pharmacy",
            "planName", "Standard",
            "number", "SUB-000001",
            "amount", "TZS 120,000.00",
            "dueDate", "2026-12-01"));
    define(
        TemplateKeys.SUBSCRIPTION_PAST_DUE,
        new Default(
            "{{pharmacyName}}: subscription invoice {{number}} is overdue",
            "Hello {{name}}, invoice {{number}} ({{amount}}) was due on {{dueDate}} and is unpaid."
                + " Pay by {{graceEnds}}, or {{pharmacyName}} will be suspended until it is paid.",
            "{{pharmacyName}}: invoice {{number}} ({{amount}}) is overdue. Pay by {{graceEnds}} to"
                + " avoid suspension."),
        new Default(
            "{{pharmacyName}}: ankara ya usajili {{number}} imepitwa na muda",
            "Habari {{name}}, ankara {{number}} ({{amount}}) ilipaswa kulipwa tarehe {{dueDate}} na"
                + " bado haijalipwa. Lipa kabla ya {{graceEnds}}, la sivyo {{pharmacyName}} itasimamishwa"
                + " hadi ilipwe.",
            "{{pharmacyName}}: ankara {{number}} ({{amount}}) imepitwa na muda. Lipa kabla ya"
                + " {{graceEnds}} kuepuka kusimamishwa."),
        Map.of(
            "name", "Amina Hassan",
            "pharmacyName", "Afya Pharmacy",
            "number", "SUB-000001",
            "amount", "TZS 120,000.00",
            "dueDate", "2026-12-01",
            "graceEnds", "2026-12-08"));
  }

  private TemplateCatalog() {}

  private static void define(String key, Default en, Default sw, Map<String, Object> sample) {
    DEFAULTS.put(key, Map.of("en", en, "sw", sw));
    SAMPLES.put(key, sample);
  }

  static boolean known(String key) {
    return DEFAULTS.containsKey(key);
  }

  static String locale(String requested) {
    if (requested == null) {
      return "en";
    }
    String language = requested.trim().toLowerCase();
    return language.startsWith("sw") ? "sw" : "en";
  }

  /** The built-in subject (null for SMS and WhatsApp) and body of a key on a channel. */
  static Default forChannel(String key, Channel channel, String locale) {
    Default base = DEFAULTS.get(key).get(locale(locale));
    return switch (channel) {
      case EMAIL -> new Default(base.subject(), base.body(), null);
      case SMS, WHATSAPP -> new Default(null, base.text(), null);
      case IN_APP -> new Default(base.subject(), base.text(), null);
    };
  }

  static Map<String, Object> sample(String key) {
    return SAMPLES.getOrDefault(key, Map.of());
  }
}
