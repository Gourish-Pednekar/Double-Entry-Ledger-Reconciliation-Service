package com.gourish.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BankCsvParser {

    private BankCsvParser() {
    }

    /**
     * Expected header: reference,date,amount,description (description may contain
     * commas).
     */
    static List<BankLineInput> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new InvalidCsvException("CSV is empty");
        }
        String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv; // tolerate a BOM
        String[] rows = text.strip().split("\\R");

        if (!rows[0].trim().equalsIgnoreCase("reference,date,amount,description")) {
            throw new InvalidCsvException("Header must be exactly: reference,date,amount,description");
        }

        List<BankLineInput> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int n = 1; n < rows.length; n++) {
            String row = rows[n];
            if (row.isBlank())
                continue;
            int line = n + 1;

            String[] c = row.split(",", 4);
            if (c.length < 3) {
                throw new InvalidCsvException("Line " + line + ": expected reference,date,amount,description");
            }

            String reference = c[0].trim();
            if (reference.isEmpty() || reference.length() > 64) {
                throw new InvalidCsvException("Line " + line + ": reference must be 1 to 64 characters");
            }
            if (!seen.add(reference)) {
                throw new InvalidCsvException("Line " + line + ": duplicate reference '" + reference + "'");
            }

            LocalDate date;
            try {
                date = LocalDate.parse(c[1].trim());
            } catch (DateTimeParseException e) {
                throw new InvalidCsvException("Line " + line + ": date must be YYYY-MM-DD");
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(c[2].trim());
            } catch (NumberFormatException e) {
                throw new InvalidCsvException("Line " + line + ": amount is not a number");
            }
            if (amount.signum() == 0) {
                throw new InvalidCsvException("Line " + line + ": amount must not be zero");
            }
            if (amount.scale() > 4 || amount.precision() - amount.scale() > 15) {
                throw new InvalidCsvException("Line " + line + ": amount has too many digits");
            }

            String description = c.length == 4 ? c[3].trim() : "";
            if (description.length() > 255) {
                throw new InvalidCsvException("Line " + line + ": description is longer than 255 characters");
            }

            out.add(new BankLineInput(reference, date, amount, description));
        }

        if (out.isEmpty()) {
            throw new InvalidCsvException("CSV has a header but no data rows");
        }
        return out;
    }
}