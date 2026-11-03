package tz.co.pharmacy.modules.inventory.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

/** Stores a list of serial numbers as one comma-separated text column; null for none. */
@Converter
public class SerialNumbers implements AttributeConverter<List<String>, String> {

  @Override
  public String convertToDatabaseColumn(List<String> serials) {
    return serials == null || serials.isEmpty() ? null : String.join(",", serials);
  }

  @Override
  public List<String> convertToEntityAttribute(String column) {
    return column == null || column.isBlank() ? List.of() : Arrays.asList(column.split(","));
  }
}
