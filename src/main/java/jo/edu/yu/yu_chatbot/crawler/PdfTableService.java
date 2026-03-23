package jo.edu.yu.yu_chatbot.crawler;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.InputStream;
import java.util.List;

/**
 * Service responsible for extracting tabular data from PDF documents using Tabula
 * and converting it into Markdown format for LLM consumption.
 */
@Service
@Slf4j
public class PdfTableService {

    public String extractPdfWithTables(InputStream pdfStream) {
        StringBuilder fullText = new StringBuilder();

        try (PDDocument document = PDDocument.load(pdfStream)) {
            SpreadsheetExtractionAlgorithm algorithm = new SpreadsheetExtractionAlgorithm();
            ObjectExtractor extractor = new ObjectExtractor(document);
            PageIterator pages = extractor.extract();

            while (pages.hasNext()) {
                Page page = pages.next();
                List<Table> tables = algorithm.extract(page);

                if (tables.isEmpty()) continue;

                for (Table table : tables) {
                    String markdownTable = convertTableToMarkdown(table);
                    if (!markdownTable.isBlank()) {
                        fullText.append(markdownTable).append("\n\n");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting PDF tables", e);
            return "";
        }

        return fullText.toString().replaceAll("\\n{3,}", "\n\n");
    }

    private String convertTableToMarkdown(Table table) {
        StringBuilder sb = new StringBuilder();
        var rows = table.getRows();
        if (rows.isEmpty()) return "";

        boolean hasHeader = false;

        for (int i = 0; i < rows.size(); i++) {
            var cells = rows.get(i);
            StringBuilder rowBuilder = new StringBuilder();
            boolean rowHasRealContent = false;

            rowBuilder.append("| ");
            for (RectangularTextContainer cell : cells) {
                String text = cell.getText()
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

                if (!text.isBlank() && !text.matches("^[\\s|X\\-]*$")) {
                    rowHasRealContent = true;
                }
                rowBuilder.append(text).append(" | ");
            }
            rowBuilder.append("\n");

            if (rowHasRealContent) {
                sb.append(rowBuilder);

                if (!hasHeader) {
                    sb.append("|");
                    for (int j = 0; j < cells.size(); j++) sb.append(" --- |");
                    sb.append("\n");
                    hasHeader = true;
                }
            }
        }
        return sb.toString();
    }
}