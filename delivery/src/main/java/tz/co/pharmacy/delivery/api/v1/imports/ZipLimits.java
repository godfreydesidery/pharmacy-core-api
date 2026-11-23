package tz.co.pharmacy.delivery.api.v1.imports;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import org.apache.commons.compress.utils.InputStreamStatistics;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * An Excel workbook is a ZIP, and a small ZIP can expand to gigabytes (a "zip bomb"): read whole,
 * it would run the API out of memory for every pharmacy (adversarial review INT-1). Before the
 * workbook is read, every part is inflated once into nothing, counting, with the same ZIP reader
 * the workbook reader uses (Apache Commons Compress, through the central directory) and the limits
 * Apache POI applies to the same files ({@code ZipSecureFile}): the number of parts, the total size
 * once expanded, and how far one part expands (its ratio, checked while it is read, past a small
 * grace size). Only stored and deflated parts are accepted, as Excel writes them.
 */
final class ZipLimits {

  /** The most parts a workbook may have; Excel's own have a few dozen. */
  static final int MAX_ENTRIES = 1_000;

  /** The most bytes all parts may expand to together. */
  static final long MAX_UNCOMPRESSED = 50L * 1024 * 1024;

  /** The most one part may expand: 100 bytes for each compressed byte (POI: 0.01). */
  static final double MAX_RATIO = 100;

  /** A part is not judged on its ratio until it has expanded this far (POI: 100 KB). */
  static final long GRACE = 100L * 1024;

  private static final Set<Integer> METHODS =
      Set.of(ZipMethod.STORED.getCode(), ZipMethod.DEFLATED.getCode());

  private ZipLimits() {}

  /** Refuses a ZIP past the limits (422); returns quietly otherwise. */
  static void check(byte[] content) {
    declaredEntries(content);
    long total = 0;
    try (ZipFile zip =
        ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get()) {
      int entries = 0;
      byte[] buffer = new byte[64 * 1024];
      for (Enumeration<ZipArchiveEntry> all = zip.getEntries(); all.hasMoreElements(); ) {
        ZipArchiveEntry entry = all.nextElement();
        if (++entries > MAX_ENTRIES) {
          throw tooManyEntries();
        }
        if (entry.isDirectory()) {
          continue;
        }
        if (!METHODS.contains(entry.getMethod()) || entry.getGeneralPurposeBit().usesEncryption()) {
          throw Refusal.invalid(
              "file_not_allowed",
              "The workbook is packed in a way Excel does not use; open it in Excel and save it"
                  + " again as .xlsx.",
              "file");
        }
        try (InputStream in = zip.getInputStream(entry)) {
          InputStreamStatistics counts = (InputStreamStatistics) in;
          for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            total += read;
            if (total > MAX_UNCOMPRESSED) {
              throw Refusal.invalid(
                  "file_too_large_uncompressed",
                  "The workbook expands to more than "
                      + MAX_UNCOMPRESSED / (1024 * 1024)
                      + " MB once opened: split it, or save it as CSV.",
                  "file");
            }
            long expanded = counts.getUncompressedCount();
            if (expanded > GRACE
                && expanded > MAX_RATIO * Math.max(1, counts.getCompressedCount())) {
              throw Refusal.invalid(
                  "file_too_compressed",
                  "A part of the workbook expands more than "
                      + (int) MAX_RATIO
                      + " times, as only a crafted file does: open it in Excel and save it again,"
                      + " or save it as CSV.",
                  "file");
            }
          }
        }
      }
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof Refusal refusal) {
        throw refusal;
      }
      throw Refusal.invalid(
          "unreadable_file", "The file could not be read as an Excel workbook.", "file");
    }
  }

  /**
   * The entry count the ZIP's end record declares, checked before the central directory is read
   * into memory: a ZIP64 marker (0xFFFF) or more than {@link #MAX_ENTRIES} is refused at once.
   */
  private static void declaredEntries(byte[] content) {
    int from = Math.max(0, content.length - (22 + 0xFFFF));
    for (int at = content.length - 22; at >= from; at--) {
      if (content[at] == 0x50
          && content[at + 1] == 0x4b
          && content[at + 2] == 0x05
          && content[at + 3] == 0x06) {
        int declared = (content[at + 10] & 0xff) | (content[at + 11] & 0xff) << 8;
        if (declared == 0xFFFF || declared > MAX_ENTRIES) {
          throw tooManyEntries();
        }
        return;
      }
    }
  }

  private static Refusal tooManyEntries() {
    return Refusal.invalid(
        "file_too_many_entries",
        "The workbook has more than " + MAX_ENTRIES + " parts, as only a crafted file does.",
        "file");
  }
}
