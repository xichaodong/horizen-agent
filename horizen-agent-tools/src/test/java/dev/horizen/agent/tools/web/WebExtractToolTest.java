package dev.horizen.agent.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

class WebExtractToolTest {
    @Test
    void stripsHtmlBoilerplateAndKeepsReadableBody() throws Exception {
        WebExtractTool.Extracted extracted =
                WebExtractTool.extractHtml(
                        """
            <html><head><title>Useful page</title><script>steal()</script></head>
            <body><nav>navigation</nav><article><h1>Headline</h1><p>Useful content.</p></article>
            <footer>footer</footer></body></html>
            """
                                .getBytes(StandardCharsets.UTF_8),
                        URI.create("https://example.com/page"),
                        "text/html");
        assertEquals("Useful page", extracted.getTitle());
        assertTrue(extracted.getContent().contains("Headline"));
        assertTrue(extracted.getContent().contains("Useful content."));
        assertFalse(extracted.getContent().contains("navigation"));
    }

    @Test
    void extractsPdfText() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            try (PDPageContentStream content =
                    new PDPageContentStream(document, document.getPage(0))) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("PDF body text");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }
        assertTrue(WebExtractTool.extractPdf(pdf).getContent().contains("PDF body text"));
    }

    @Test
    void returnsHeadAndTailForLongContent() {
        String content = "head\n" + "x".repeat(5_000) + "\ntail";
        String result = WebExtractTool.truncate(content, 2_000);
        assertTrue(result.contains("head"));
        assertTrue(result.contains("tail"));
        assertTrue(result.contains("middle omitted"));
    }
}
