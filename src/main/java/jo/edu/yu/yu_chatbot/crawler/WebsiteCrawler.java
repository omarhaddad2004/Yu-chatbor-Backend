package jo.edu.yu.yu_chatbot.crawler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class WebsiteCrawler {

    private final PdfTableService pdfTableService;

    public CrawledPage crawl(String url) {
        try {
            String normalized = normalizeUrl(url);
            if (normalized == null) return null;

            String lowerUrl = normalized.toLowerCase();

            if (lowerUrl.endsWith(".pdf")) {
                return crawlPdf(normalized);
            } else if (lowerUrl.endsWith(".doc") || lowerUrl.endsWith(".docx")) {
                return crawlWordDoc(normalized);
            } else {
                return crawlHtml(normalized);
            }
        } catch (Throwable e) {
            log.error("ERROR: {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) return null;

        // Remove trailing slashes and fragments
        url = url.trim()
                .replaceAll("/+$", "")
                .replaceAll("#.*$", "");

        // Normalize index.html, index.php, index.htm
        url = url.replaceAll("/(index\\.(html?|php))$", "");

        return url;
    }

    private CrawledPage crawlHtml(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(60_000)
                .followRedirects(true)
                .ignoreHttpErrors(true) // Continue processing even with 404 or 500 HTTP errors
                .get();

        // Check page type: Is it a faculty member card or a comprehensive profile page?
        if (isFacultyMemberPage(url, doc)) {
            return crawlFacultyMemberPage(doc, url);
        }

        Set<String> links = new HashSet<>();
        String html = doc.html();

        // Extract anchor links
        Elements anchors = doc.select("a[href]");
        links.addAll(anchors.stream()
                .map(a -> a.attr("abs:href"))
                .filter(href -> href.startsWith("http"))
                .map(this::normalizeUrl) // Normalize each link
                .filter(Objects::nonNull)
                .filter(this::isValidLink)
                .collect(Collectors.toSet()));

        Elements iframes = doc.select("iframe[src]");
        links.addAll(iframes.stream()
                .map(iframe -> iframe.attr("abs:src"))
                .filter(src -> src.startsWith("http"))
                .map(this::normalizeUrl)
                .filter(Objects::nonNull)
                .filter(this::isValidLink)
                .collect(Collectors.toSet()));

        links.addAll(extractJsLinks(html, url));
        links.addAll(extractHiddenFacultyLinksFallback(html));

        Elements metaLinks = doc.select("meta[property=og:url], meta[name=twitter:url]");
        for (Element meta : metaLinks) {
            String content = normalizeUrl(meta.attr("content"));
            if (content != null && content.startsWith("http") && isValidLink(content)) {
                links.add(content);
            }
        }

        doc.select("script, style, nav, header, footer, img, iframe, noscript, .nav, .footer, .menu, .sidebar").remove();

        String text = extractCleanText(doc.body());

        return CrawledPage.builder()
                .url(url)
                .title(doc.title())
                .content(text)
                .contentType("HTML")
                .links(links)
                .build();
    }

    // Smart check: Determines if the page is a faculty member card or full profile
    private boolean isFacultyMemberPage(String url, Document doc) {
        String lowerUrl = url.toLowerCase();

        // 1. Check URL structure (Card for small profile, AcadURL for comprehensive profile)
        if (lowerUrl.contains("fmd.yu.edu.jo") && (lowerUrl.contains("card=") || lowerUrl.contains("acadurl="))) {
            return true;
        }

        // 2. Content-based detection
        if (lowerUrl.contains("faculty") || lowerUrl.contains("staff") || lowerUrl.contains("member")) {
            Elements tables = doc.select("table");
            for (Element table : tables) {
                String tableText = table.text().toLowerCase();
                if (tableText.contains("email") || tableText.contains("office") ||
                        tableText.contains("phone") || tableText.contains("البريد") ||
                        tableText.contains("المكتب")) {
                    return true;
                }
            }
        }

        return false;
    }

    // Enhanced extraction for faculty pages (Immediate deep dive to merge card with full profile)
    private CrawledPage crawlFacultyMemberPage(Document doc, String url) {
        StringBuilder content = new StringBuilder();

        // 1: Extract Name
        String name = extractDoctorName(doc);
        if (!name.isEmpty()) {
            content.append("# ").append(name).append("\n\n");
        }

        // 2: Extract tables from the primary card
        Elements tables = doc.select("table");
        for (Element table : tables) {
            String tableMarkdown = extractFacultyTableAsMarkdown(table);
            if (!tableMarkdown.isBlank()) {
                content.append(tableMarkdown).append("\n\n");
            }
        }

        // 3: Extract text and paragraphs from the primary card
        Elements textElements = doc.select("p, div, span, td, th, h1, h2, h3, h4");
        Set<String> addedTexts = new HashSet<>();
        for (Element el : textElements) {
            String text = el.ownText().trim();
            if (text.length() > 10 && !addedTexts.contains(text)) {
                if (!isGenericText(text)) {
                    content.append(text).append("\n\n");
                    addedTexts.add(text);
                }
            }
        }

        // Immediate Deep Dive: Search for AcadURL and fetch comprehensive data immediately
        String deepLink = findDeepProfileLink(doc.html());
        if (deepLink != null) {
            log.info("Deep diving into full profile immediately: {}", deepLink);
            String extraContent = fetchExtraProfileData(deepLink);
            if (!extraContent.isEmpty()) {
                content.append("\n\n--- السيرة الذاتية والأبحاث (الصفحة الشاملة) ---\n\n");
                content.append(extraContent);
            }
        } else {
            // Search inside iframes as a fallback mechanism
            Elements iframes = doc.select("iframe[src]");
            for (Element iframe : iframes) {
                String src = iframe.attr("abs:src");
                if (src.toLowerCase().contains("acadurl=")) {
                    log.info("Deep diving into full profile via iframe: {}", src);
                    String extraContent = fetchExtraProfileData(normalizeUrl(src));
                    if (!extraContent.isEmpty()) {
                        content.append("\n\n--- التفاصيل الشاملة ---\n\n");
                        content.append(extraContent);
                        break; // One successful merge is sufficient to prevent duplication
                    }
                }
            }
        }

        if (content.length() < 100) {
            log.warn("Low content from structured extraction for: {}. Using full body...", url);
            content.setLength(0);
            content.append(extractCleanText(doc.body()));
        }

        String finalContent = content.toString().trim();

        // Extract standard links to pass to the queue as usual
        Set<String> links = new HashSet<>();
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String href = a.attr("abs:href");
            if (href.startsWith("http") && isValidLink(href)) {
                links.add(normalizeUrl(href));
            }
        }
        links.addAll(extractHiddenFacultyLinksFallback(doc.html()));

        return CrawledPage.builder()
                .url(url)
                .title(name.isEmpty() ? "Faculty Member" : name)
                .content(finalContent)
                .contentType("FACULTY-MEMBER")
                .links(links)
                .build();
    }

    // Helper method to fetch and merge comprehensive profile content
    private String fetchExtraProfileData(String url) {
        StringBuilder extraContent = new StringBuilder();
        try {
            Document extraDoc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(30_000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .get();

            // Extract tables (e.g., experiences, publications, etc.)
            Elements tables = extraDoc.select("table");
            for (Element table : tables) {
                String tableMarkdown = extractFacultyTableAsMarkdown(table);
                if (!tableMarkdown.isBlank()) {
                    extraContent.append(tableMarkdown).append("\n\n");
                }
            }

            // Extract lists
            Elements lists = extraDoc.select("ul, ol");
            for (Element list : lists) {
                Elements items = list.select("li");
                if (!items.isEmpty()) {
                    for (Element item : items) {
                        String text = item.text().trim();
                        if (text.length() > 10 && !isGenericText(text)) {
                            extraContent.append("• ").append(text).append("\n");
                        }
                    }
                    extraContent.append("\n");
                }
            }

            // If no clear lists or tables are found, extract direct paragraph text
            if (extraContent.length() < 50) {
                Elements paragraphs = extraDoc.select("p, div.content, .biography, .about");
                for(Element p : paragraphs){
                    String txt = p.text().trim();
                    if(txt.length() > 20 && !isGenericText(txt)){
                        extraContent.append(txt).append("\n\n");
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to fetch deep profile: {} - {}", url, e.getMessage());
        }
        return extraContent.toString().trim();
    }

    // Method for quick regex-based search of AcadURL link within page text
    private String findDeepProfileLink(String html) {
        // Regex modified to accommodate the dot character
        Pattern acadPattern = Pattern.compile(
                "AcadURL=([a-zA-Z0-9+/=%_.-]+)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher acadMatcher = acadPattern.matcher(html);
        if (acadMatcher.find()) {
            return normalizeUrl("https://fmd.yu.edu.jo/facweb/HomePage.aspx?AcadURL=" + acadMatcher.group(1));
        }
        return null;
    }


    private String extractDoctorName(Document doc) {
        String[] possibleIds = {"lblArName", "lblEnName", "lblName", "ctl00_lblName"};
        for (String id : possibleIds) {
            Element el = doc.getElementById(id);
            if (el != null && !el.text().isBlank()) {
                return el.text().trim();
            }
        }

        Element heading = doc.select("h1, h2").first();
        if (heading != null && !heading.text().isBlank()) {
            String text = heading.text().trim();
            if (text.length() < 100) {
                return text;
            }
        }

        Elements tables = doc.select("table");
        for (Element table : tables) {
            Elements rows = table.select("tr");
            for (Element row : rows) {
                String rowText = row.text().toLowerCase();
                if (rowText.contains("الاسم") || rowText.contains("name")) {
                    Elements cells = row.select("td");
                    if (cells.size() > 1) {
                        String nameCandidate = cells.get(1).text().trim();
                        if (!nameCandidate.isEmpty() && nameCandidate.length() < 100) {
                            return nameCandidate;
                        }
                    }
                }
            }
        }
        return "";
    }

    private boolean isGenericText(String text) {
        String lower = text.toLowerCase();
        return lower.equals("home") || lower.equals("back") ||
                lower.equals("english") || lower.equals("العربية") ||
                lower.equals("login") || lower.equals("logout") ||
                lower.length() < 3;
    }

    private String extractFacultyTableAsMarkdown(Element table) {
        StringBuilder sb = new StringBuilder();
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return "";

        for (Element row : rows) {
            List<String> cellTexts = new ArrayList<>();

            for (Element cell : row.select("th, td")) {
                String text = cell.text().trim().replaceAll("\\s+", " ");
                // Ignore generic/stop words inside tables as well
                if (!text.isBlank() && !isGenericText(text)) {
                    cellTexts.add(text);
                }
            }

            if (!cellTexts.isEmpty()) {
                sb.append("| ").append(String.join(" | ", cellTexts)).append(" |\n");
            }
        }
        return sb.toString();
    }

    private Set<String> extractJsLinks(String html, String baseUrl) {
        Set<String> links = new HashSet<>();

        List<Pattern> patterns = List.of(
                Pattern.compile("(?:href=|url:|window\\.location=|location\\.href=|window\\.open\\()[\"']([^\"'\\)]+)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\$state\\.go\\([\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("data-(?:url|href|link)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:onclick)=[\"'].*?(?:location|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                String link = normalizeJsLink(matcher.group(1), baseUrl);
                if (link != null && isValidLink(link)) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    private String normalizeJsLink(String link, String baseUrl) {
        if (link == null || link.isEmpty()) return null;
        try {
            if (link.startsWith("http")) return normalizeUrl(link);

            URL base = new URL(baseUrl);
            if (link.startsWith("/")) {
                return normalizeUrl(base.getProtocol() + "://" + base.getHost() + link);
            }

            String path = base.getPath();
            String parentPath = path.substring(0, path.lastIndexOf("/") + 1);
            return normalizeUrl(base.getProtocol() + "://" + base.getHost() + parentPath + link);
        } catch (Exception e) {
            return null;
        }
    }

    // Enhanced Regex to catch both Card and AcadURL variations
    private Set<String> extractHiddenFacultyLinksFallback(String html) {
        Set<String> hiddenLinks = new HashSet<>();

        // Regex modified to accommodate the dot character
        Pattern fullPattern = Pattern.compile(
                "https?://fmd\\.yu\\.edu\\.jo/[^\"'\\s<>]*(card|acadurl)=[a-zA-Z0-9+/=%_.-]+",
                Pattern.CASE_INSENSITIVE
        );
        Matcher fullMatcher = fullPattern.matcher(html);
        while (fullMatcher.find()) {
            hiddenLinks.add(normalizeUrl(fullMatcher.group()));
        }

        // Relative path extraction
        Pattern relativePattern = Pattern.compile(
                "(?:External|Internal)AcademicsCard\\.aspx\\?card=[a-zA-Z0-9+/=%_.-]+",
                Pattern.CASE_INSENSITIVE
        );
        Matcher relMatcher = relativePattern.matcher(html);
        while (relMatcher.find()) {
            String link = "https://fmd.yu.edu.jo/" + relMatcher.group();
            hiddenLinks.add(normalizeUrl(link));
        }

        // Relative AcadURL extraction
        Pattern relativeAcadPattern = Pattern.compile(
                "facweb/HomePage\\.aspx\\?AcadURL=[a-zA-Z0-9+/=%_.-]+",
                Pattern.CASE_INSENSITIVE
        );
        Matcher acadMatcher = relativeAcadPattern.matcher(html);
        while (acadMatcher.find()) {
            String link = "https://fmd.yu.edu.jo/" + acadMatcher.group();
            hiddenLinks.add(normalizeUrl(link));
        }

        // Onclick extraction
        Pattern onclickPattern = Pattern.compile(
                "(?:onclick|data-url)=[\"'].*?(fmd\\.yu\\.edu\\.jo[^\"']*(card|acadurl)=[a-zA-Z0-9+/=%_.-]+)[\"']",
                Pattern.CASE_INSENSITIVE
        );
        Matcher onclickMatcher = onclickPattern.matcher(html);
        while (onclickMatcher.find()) {
            String link = "https://" + onclickMatcher.group(1);
            hiddenLinks.add(normalizeUrl(link));
        }

        return hiddenLinks;
    }

    private boolean isValidLink(String href) {
        if (href == null || href.isEmpty()) return false;
        String lower = href.toLowerCase();

        return !(lower.contains("facebook.com") || lower.contains("twitter.com") ||
                lower.contains("linkedin.com") || lower.contains("instagram.com") ||
                lower.contains("youtube.com") || lower.contains("sharearticle") ||
                lower.contains("mailto:") || lower.contains("tel:") ||
                lower.contains("javascript:") ||
                lower.endsWith(".css") || lower.endsWith(".js") ||
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".mp4") || lower.endsWith(".zip") ||
                lower.endsWith(".rar") || lower.contains("opensearch") ||
                lower.contains("/vt") || lower.contains("fonts.googleapis.com"));
    }

    private CrawledPage crawlWordDoc(String url) throws Exception {
        URL docUrl = new URL(url);
        try (InputStream is = docUrl.openStream()) {
            ToHTMLContentHandler handler = new ToHTMLContentHandler();
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            parser.parse(is, handler, metadata, new ParseContext());

            Document doc = Jsoup.parse(handler.toString());
            String text = extractCleanText(doc.body());
            String title = metadata.get("title");

            return CrawledPage.builder()
                    .url(url)
                    .title(title != null ? title : url)
                    .content(text)
                    .contentType("DOC")
                    .links(Set.of())
                    .build();
        }
    }

    private CrawledPage crawlPdf(String url) throws Exception {
        URL pdfUrl = new URL(url);
        String text = "";
        String method = "";
        String lowerUrl = url.toLowerCase();

        boolean isComplexPdf = lowerUrl.matches(".*(plan|schedule|table|syllabus|program|bsc|master|course|degree|خطة|برنامج).*");

        try (InputStream is = pdfUrl.openStream()) {
            if (isComplexPdf) {
                log.info("Complex PDF detected. Using Tabula: {}", url);
                text = pdfTableService.extractPdfWithTables(is);
                method = "PDF-Tabula";

                if (text == null || text.trim().length() < 100) {
                    log.warn("Tabula short output. Fallback to PDFBox: {}", url);
                    try (InputStream is2 = pdfUrl.openStream()) {
                        text = extractPdfWithPdfBox(is2);
                        method = "PDF-Box-Fallback";
                    }
                }
            } else {
                log.info("Regular PDF. Using PDFBox: {}", url);
                text = extractPdfWithPdfBox(is);
                method = "PDF-Box";
            }
        }

        return CrawledPage.builder()
                .url(url)
                .title(url)
                .content(text != null ? text : "")
                .contentType(method)
                .links(Set.of())
                .build();
    }

    private String extractPdfWithPdfBox(InputStream stream) {
        try (PDDocument document = PDDocument.load(stream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (text == null) return "";

            return text.replaceAll("(?<![\\.\\?!؟:])\\n+", " ")
                    .replaceAll("\\s{2,}", " ")
                    .trim();

        } catch (IOException e) {
            log.error("PDFBox failed: {}", e.getMessage());
            return "";
        }
    }

    private String extractCleanText(Element body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();

        Elements elements = body.select(
                "h1, h2, h3, h4, h5, h6, p, ul, ol, table, " +
                        "div.content, article, main, section, " +
                        ".profile-info, .faculty-member, .staff-card, " +
                        ".department-info, .course-description, " +
                        ".news-item, .event-details, .announcement, .contact-info"
        );

        for (Element el : elements) {
            if (el.tagName().equals("table")) {
                String tableText = extractFacultyTableAsMarkdown(el);
                if (!tableText.isEmpty()) {
                    sb.append(tableText).append("\n\n");
                }
            } else {
                String text = el.text().trim();
                // Preserve course abbreviations and office numbers (more than 2 characters)
                if (text.length() >= 2 && !isGenericText(text)) {
                    sb.append(text).append("\n");
                }
            }
        }

        String finalContent = sb.toString().trim();
        if (finalContent.isEmpty() && body.text().length() > 100) {
            return body.text().trim();
        }
        return finalContent;
    }
}