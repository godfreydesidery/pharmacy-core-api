package tz.co.pharmacy.modules.files.storage;

import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The one-off copy of the local file directory to S3, for the move off a volume: start the API with
 * {@code --pharmacy.files.copy-local-to-s3=true} (and {@code
 * --spring.main.web-application-type=none}, so it serves nothing), with the volume mounted and the
 * {@code pharmacy.files.s3.*} settings and AWS credentials of the target. It copies {@code
 * pharmacy.files.local.root} with {@link LocalToS3Copy}, whichever store {@code
 * pharmacy.files.storage} names, logs the report and exits: status 0 when every file is in S3 and
 * checked, 1 otherwise. {@code pharmacy.files.copy-verify=full} reads every object back; {@code
 * pharmacy.files.copy-exit=false} keeps the process running afterwards (tests).
 */
@Component
@ConditionalOnProperty(name = "pharmacy.files.copy-local-to-s3", havingValue = "true")
class LocalToS3CopyRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(LocalToS3CopyRunner.class);

  private final Environment environment;
  private final ConfigurableApplicationContext context;

  LocalToS3CopyRunner(Environment environment, ConfigurableApplicationContext context) {
    this.environment = environment;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!arguments.containsOption("pharmacy.files.copy-local-to-s3")) {
      // Set in the environment it would copy and exit on every restart of a serving API.
      LOG.warn(
          "pharmacy.files.copy-local-to-s3 is honoured only as a command-line flag; ignored here.");
      return;
    }
    Path root = Path.of(environment.getProperty("pharmacy.files.local.root", "target/files"));
    LocalToS3Copy.Verify verify =
        LocalToS3Copy.Verify.valueOf(
            environment
                .getProperty("pharmacy.files.copy-verify", "head")
                .trim()
                .toUpperCase(Locale.ROOT));
    S3Settings settings = S3Settings.from(environment);
    LOG.info(
        "Copying {} to s3://{}/{} (check: {})",
        root.toAbsolutePath(),
        settings.bucket(),
        settings.prefix(),
        verify);
    long started = System.nanoTime();
    LocalToS3Copy.Report report;
    try (S3ObjectStorage target = new S3ObjectStorage(settings)) {
      report = new LocalToS3Copy(root, target, verify).run();
    }
    long seconds = (System.nanoTime() - started) / 1_000_000_000L;
    LOG.info(
        "Copy to S3 finished in {} s: {} files, {} copied ({} bytes), {} already there and"
            + " checked, {} vanished while copying, {} failed",
        seconds,
        report.files(),
        report.copied(),
        report.bytesCopied(),
        report.skipped(),
        report.vanished(),
        report.failed().size());
    report.failed().forEach(failure -> LOG.error("Not copied: {}", failure));
    if (environment.getProperty("pharmacy.files.copy-exit", Boolean.class, true)) {
      int status = report.ok() ? 0 : 1;
      System.exit(SpringApplication.exit(context, () -> status));
    }
  }
}
