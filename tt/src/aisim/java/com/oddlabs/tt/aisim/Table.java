package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text tables of the reports: aligned columns and short numbers. */
final class Table {
    private Table() {
    }

    /** The rows as aligned lines: the first column left-aligned, the others right-aligned, two spaces indented. */
    static @NonNull List<String> align(@NonNull List<List<String>> rows) {
        return align(rows, "lr");
    }

    /**
     * The rows as aligned lines, two spaces indented. {@code layout} aligns column i by its i-th letter, l (left) or r
     * (right); its last letter holds for the columns after it.
     */
    static @NonNull List<String> align(@NonNull List<List<String>> rows, @NonNull String layout) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] width = new int[columns];
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                width[i] = Math.max(width[i], row.get(i).length());
            }
        }
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder(" ");
            for (int i = 0; i < row.size(); i++) {
                String align = layout.charAt(Math.min(i, layout.length() - 1)) == 'l' ? "-" : "";
                line.append(' ').append(String.format(Locale.ROOT, "%" + align + width[i] + "s", row.get(i)));
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    /** A mean as short text: whole numbers and values from 10 up without decimals, others with one. */
    static @NonNull String number(double value) {
        boolean whole = value == Math.rint(value) || Math.abs(value) >= 10;
        return String.format(Locale.ROOT, whole ? "%.0f" : "%.1f", value);
    }
}
