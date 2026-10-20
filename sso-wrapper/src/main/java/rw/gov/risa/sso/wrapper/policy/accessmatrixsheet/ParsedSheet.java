package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import java.util.List;
import java.util.Map;

/**
 * A verified, read file.
 *
 * @param clients applications both in the signed metadata and still a column (metadata order)
 * @param marks employee number -> (client id -> marked); an unreadable cell is absent here and
 *     present in {@code problems}; insertion order = sheet order
 * @param rows employee number -> spreadsheet row
 */
public record ParsedSheet(SheetMeta meta, List<String> clients, Map<String, Map<String, Boolean>> marks,
                          Map<String, Integer> rows, List<Problem> problems) {
}
