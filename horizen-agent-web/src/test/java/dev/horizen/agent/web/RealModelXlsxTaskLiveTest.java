package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.bos.BosArtifactContentStore;
import dev.horizen.agent.web.api.AgentService;
import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.api.chat.ChatApi;
import dev.horizen.agent.web.api.session.SessionApi;
import dev.horizen.agent.web.config.ArtifactProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import redis.clients.jedis.JedisPooled;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/** 显式启用的真实模型验收：上传 XLSX → 沙箱编辑 → BOS Artifact → 持久化卡片和历史。 */
@Tag("live-full-task")
@EnabledIfSystemProperty(named = "horizen.real.xlsx.live", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "horizen.agent.sandbox.e2b.enabled=true")
class RealModelXlsxTaskLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "HORIZEN_XLSX_ACCEPTANCE_20260928";
    private static final String FOLLOWUP_MARKER = "HORIZEN_XLSX_FOLLOWUP_20260928";
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private AgentService service;
    @Autowired private RuntimeStorageProperties storageProperties;
    @Autowired private ArtifactProperties artifactProperties;
    @Autowired private E2bSandboxProperties sandboxProperties;

    @Test
    void realModelEditsUploadedWorkbookAndPublishesRecoverableArtifact() throws Exception {
        Assumptions.assumeTrue(service.status().isReady(), "remote model is not configured");
        Assumptions.assumeTrue(storageProperties.distributed(), "distributed storage is required");
        Assumptions.assumeTrue(artifactProperties.isEnabled(), "BOS Artifact storage is required");
        Assumptions.assumeTrue(sandboxProperties.isEnabled(), "E2B sandbox is required");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        ExecutionIdentity identity = new ExecutionIdentity("xlsx-live-" + suffix, "acceptance");
        String sessionId = "xlsx-live-" + suffix.substring(0, 24);
        byte[] source = Files.readAllBytes(fixture());
        String inputArtifactId = null;
        String outputArtifactId = null;
        String followupArtifactId = null;

        try {
            ArtifactApi.ArtifactResponse uploaded =
                    service.uploadArtifact(
                            identity,
                            new MockMultipartFile(
                                    "file", "source-sales.xlsx", XLSX_MEDIA_TYPE, source));
            inputArtifactId = uploaded.getArtifactId();
            String inputId = inputArtifactId;

            ChatApi.ChatRequest request =
                    new ChatApi.ChatRequest(
                            sessionId, prompt(), "xlsx-request-" + suffix, List.of(inputId));
            List<ChatApi.ChatStreamEvent> events =
                    service.streamChat(identity, request)
                            .collectList()
                            .block(Duration.ofMinutes(10));
            assertNotNull(events);
            assertFalse(events.isEmpty());
            assertFalse(
                    events.stream().anyMatch(event -> "error".equals(event.getType())),
                    failureSummary(events));
            assertTrue(
                    events.stream().anyMatch(event -> "done".equals(event.getType())),
                    failureSummary(events));

            List<String> tools =
                    events.stream()
                            .filter(event -> "tool_start".equals(event.getType()))
                            .map(ChatApi.ChatStreamEvent::getToolName)
                            .toList();
            assertTrue(tools.contains("load_artifact"), tools.toString());
            assertTrue(tools.contains("execute"), tools.toString());
            assertTrue(tools.contains("deliver_artifact"), tools.toString());

            JsonNode block = artifactBlock(events);
            outputArtifactId = block.path("data").path("artifactId").asText();
            assertFalse(outputArtifactId.isBlank(), block.toString());
            assertFalse(outputArtifactId.equals(inputId));
            assertEquals("reviewed-sales.xlsx", block.path("data").path("title").asText());
            assertEquals(inputId, block.path("data").path("parentArtifactId").asText());

            String outputId = outputArtifactId;
            ArtifactApi.ArtifactDownloadResponse download =
                    service.artifactDownloadUrl(
                            identity, new ArtifactApi.ArtifactDownloadRequest(outputId, 300));
            byte[] result = download(download.getUrl());
            assertWorkbook(result);
            clearAgentState(identity.getOwnerKey(), sessionId);

            List<ChatApi.ChatStreamEvent> followupEvents =
                    service.streamChat(
                                    identity,
                                    new ChatApi.ChatRequest(
                                            sessionId,
                                            followupPrompt(),
                                            "xlsx-followup-" + suffix,
                                            List.of()))
                            .collectList()
                            .block(Duration.ofMinutes(10));
            assertNotNull(followupEvents);
            assertFalse(
                    followupEvents.stream().anyMatch(event -> "error".equals(event.getType())),
                    failureSummary(followupEvents));
            assertTrue(
                    followupEvents.stream().anyMatch(event -> "done".equals(event.getType())),
                    failureSummary(followupEvents));
            List<String> followupTools =
                    followupEvents.stream()
                            .filter(event -> "tool_start".equals(event.getType()))
                            .map(ChatApi.ChatStreamEvent::getToolName)
                            .toList();
            assertTrue(followupTools.contains("load_artifact"), followupTools.toString());
            assertTrue(followupTools.contains("execute"), followupTools.toString());
            assertTrue(followupTools.contains("deliver_artifact"), followupTools.toString());

            JsonNode followupBlock = artifactBlock(followupEvents);
            followupArtifactId = followupBlock.path("data").path("artifactId").asText();
            assertFalse(followupArtifactId.isBlank(), followupBlock.toString());
            assertEquals(
                    "reviewed-sales-v2.xlsx", followupBlock.path("data").path("title").asText());
            assertEquals(outputId, followupBlock.path("data").path("parentArtifactId").asText());
            String followupId = followupArtifactId;
            ArtifactApi.ArtifactDownloadResponse followupDownload =
                    service.artifactDownloadUrl(
                            identity, new ArtifactApi.ArtifactDownloadRequest(followupId, 300));
            assertFollowupWorkbook(download(followupDownload.getUrl()));

            SessionApi.SessionMessagesResponse restored =
                    service.sessionMessages(identity, sessionId);
            assertTrue(
                    restored.getMessages().stream()
                            .anyMatch(
                                    message ->
                                            "user".equals(message.getRole())
                                                    && message.attachments().stream()
                                                            .anyMatch(
                                                                    artifact ->
                                                                            inputId.equals(
                                                                                    artifact
                                                                                            .getArtifactId()))));
            assertTrue(
                    restored.getMessages().stream()
                            .anyMatch(
                                    message ->
                                            "assistant".equals(message.getRole())
                                                    && !message.getContent().isBlank()));
            assertTrue(
                    restored.getPresentations().stream()
                            .anyMatch(
                                    card ->
                                            outputId.equals(
                                                    String.valueOf(
                                                            card.getData().get("artifactId")))));
            assertTrue(
                    restored.getPresentations().stream()
                            .anyMatch(
                                    card ->
                                            followupId.equals(
                                                    String.valueOf(
                                                            card.getData().get("artifactId")))));
            assertTrue(
                    restored.getMessages().stream()
                            .anyMatch(
                                    message ->
                                            "user".equals(message.getRole())
                                                    && message.getContent().contains("继续处理")
                                                    && message.attachments().stream()
                                                            .anyMatch(
                                                                    artifact ->
                                                                            outputId.equals(
                                                                                    artifact
                                                                                            .getArtifactId()))));
        } finally {
            cleanup(identity.getOwnerKey(), sessionId);
        }
    }

    private static String prompt() {
        return """
                修改本轮上传的 source-sales.xlsx，并交付一个新的 Excel 文件。必须完成以下步骤：
                这是确定性验收任务，不要搜索或读取 Skill，不要浏览目录；除 load_artifact、execute、
                deliver_artifact 外不要调用其他工具。修改和自检尽量合并到一次 Python 执行中。
                1. 调用 load_artifact，把上传的 Artifact 加载为工作区 input/source-sales.xlsx。
                2. 调用 execute，在沙箱中使用 Python 处理 XLSX；可以使用 openpyxl。禁止 Base64，禁止联网。
                3. 保留 Sales Data 工作表、原始数据和已有公式。在 E1 写入 Review Status，E2:E4 写入 Reviewed，E5 留空。
                4. 新建或替换 Summary 工作表：A1 必须精确写入 HORIZEN_XLSX_ACCEPTANCE_20260928；
                   A2 写 Total Revenue；B2 必须使用公式 ='Sales Data'!D5，不能写死数值。
                5. 保存为 outputs/reviewed-sales.xlsx。不要覆盖输入文件。
                6. 调用 deliver_artifact，filePath=outputs/reviewed-sales.xlsx，
                   fileName=reviewed-sales.xlsx。只交付这个最终文件。
                完成后用一句话说明已完成，不要输出本地路径，不要询问用户。
                """;
    }

    private static String followupPrompt() {
        return """
                继续处理刚才生成的 reviewed-sales.xlsx，不要让我重新上传文件。
                这是确定性验收任务，不要搜索或读取 Skill，不要浏览目录；除 load_artifact、execute、
                deliver_artifact 外不要调用其他工具。根据系统提供的本会话最近 Artifact 元数据定位
                reviewed-sales.xlsx，并调用 load_artifact 加载它。
                使用 Python 和 openpyxl 保留全部已有工作表、数据和公式，把 Sales Data!E2:E4 改成“已复核”，
                在 Summary!A3 精确写入 HORIZEN_XLSX_FOLLOWUP_20260928。
                保存为 outputs/reviewed-sales-v2.xlsx，再调用 deliver_artifact，
                filePath=outputs/reviewed-sales-v2.xlsx，fileName=reviewed-sales-v2.xlsx。
                只交付这个最终文件，完成后用一句话回复，不要输出本地路径。
                """;
    }

    private static JsonNode artifactBlock(List<ChatApi.ChatStreamEvent> events) throws Exception {
        ChatApi.ChatStreamEvent presentation =
                events.stream()
                        .filter(event -> "presentation_created".equals(event.getType()))
                        .filter(event -> event.getDetails() != null)
                        .filter(event -> event.getDetails().contains("artifact_card"))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        JsonNode value = JSON.readTree(presentation.getDetails());
        return value.has("block") ? value.path("block") : value;
    }

    private static Path fixture() {
        Path module = Path.of("").toAbsolutePath().normalize();
        Path path = module.resolve("../examples/real-model-xlsx/source-sales.xlsx").normalize();
        if (!Files.isRegularFile(path)) {
            path = module.resolve("examples/real-model-xlsx/source-sales.xlsx").normalize();
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing real-model XLSX acceptance fixture");
        }
        return path;
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(20))
                        .build()
                        .send(
                                HttpRequest.newBuilder(URI.create(url))
                                        .timeout(Duration.ofMinutes(2))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(
                response.statusCode() >= 200 && response.statusCode() < 300,
                "artifact download HTTP " + response.statusCode());
        return response.body();
    }

    private static void assertWorkbook(byte[] bytes) throws Exception {
        assertTrue(bytes.length > 1000, "delivered workbook is unexpectedly small");
        Map<String, String> xml = unzipXml(bytes);
        assertTrue(xml.containsKey("[Content_Types].xml"), "not an OOXML workbook");
        List<String> sharedStrings = sharedStrings(xml.get("xl/sharedStrings.xml"));
        Document sales = parseXml(xml.get(sheetPath(xml, "Sales Data")));
        Document summary = parseXml(xml.get(sheetPath(xml, "Summary")));
        assertEquals("Review Status", cellText(sales, "E1", sharedStrings));
        assertEquals("Reviewed", cellText(sales, "E2", sharedStrings));
        assertEquals("Reviewed", cellText(sales, "E3", sharedStrings));
        assertEquals("Reviewed", cellText(sales, "E4", sharedStrings));
        assertTrue(cellText(sales, "E5", sharedStrings).isBlank());
        assertEquals(MARKER, cellText(summary, "A1", sharedStrings));
        assertEquals("Total Revenue", cellText(summary, "A2", sharedStrings));
        String formula = cellFormula(summary, "B2");
        assertTrue(
                formula.contains("'Sales Data'!D5") || formula.contains("Sales Data!D5"),
                "Summary total must reference Sales Data!D5; formula=" + formula);
    }

    private static void assertFollowupWorkbook(byte[] bytes) throws Exception {
        assertTrue(bytes.length > 1000, "follow-up workbook is unexpectedly small");
        Map<String, String> xml = unzipXml(bytes);
        List<String> sharedStrings = sharedStrings(xml.get("xl/sharedStrings.xml"));
        Document sales = parseXml(xml.get(sheetPath(xml, "Sales Data")));
        Document summary = parseXml(xml.get(sheetPath(xml, "Summary")));
        assertEquals("已复核", cellText(sales, "E2", sharedStrings));
        assertEquals("已复核", cellText(sales, "E3", sharedStrings));
        assertEquals("已复核", cellText(sales, "E4", sharedStrings));
        assertEquals(FOLLOWUP_MARKER, cellText(summary, "A3", sharedStrings));
        String formula = cellFormula(summary, "B2");
        assertTrue(
                formula.contains("'Sales Data'!D5") || formula.contains("Sales Data!D5"),
                "follow-up workbook lost Summary formula: " + formula);
    }

    private static Map<String, String> unzipXml(byte[] bytes) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && (entry.getName().endsWith(".xml")
                                || entry.getName().endsWith(".rels")
                                || "[Content_Types].xml".equals(entry.getName()))) {
                    byte[] content = input.readNBytes(5 * 1024 * 1024);
                    values.put(entry.getName(), new String(content, StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private static String sheetPath(Map<String, String> xml, String name) throws Exception {
        Document workbook = parseXml(xml.get("xl/workbook.xml"));
        String relationId = null;
        NodeList sheets = workbook.getElementsByTagNameNS("*", "sheet");
        for (int index = 0; index < sheets.getLength(); index++) {
            Element sheet = (Element) sheets.item(index);
            if (name.equals(sheet.getAttribute("name"))) {
                relationId =
                        sheet.getAttributeNS(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id");
                if (relationId.isBlank()) relationId = sheet.getAttribute("r:id");
                break;
            }
        }
        assertNotNull(relationId, "missing worksheet " + name);
        Document relationships = parseXml(xml.get("xl/_rels/workbook.xml.rels"));
        NodeList nodes = relationships.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element relationship = (Element) nodes.item(index);
            if (!relationId.equals(relationship.getAttribute("Id"))) continue;
            String target = relationship.getAttribute("Target").replace('\\', '/');
            if (target.startsWith("/")) return target.substring(1);
            while (target.startsWith("../")) target = target.substring(3);
            return target.startsWith("xl/") ? target : "xl/" + target;
        }
        throw new AssertionError("missing worksheet relationship for " + name);
    }

    private static List<String> sharedStrings(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return List.of();
        Document document = parseXml(raw);
        NodeList items = document.getElementsByTagNameNS("*", "si");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) {
            result.add(descendantText(items.item(index), "t"));
        }
        return result;
    }

    private static String cellText(Document sheet, String reference, List<String> sharedStrings) {
        Element cell = cell(sheet, reference);
        if (cell == null) return "";
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) return descendantText(cell, "t");
        String raw = descendantText(cell, "v");
        if ("s".equals(type) && !raw.isBlank()) return sharedStrings.get(Integer.parseInt(raw));
        return raw;
    }

    private static String cellFormula(Document sheet, String reference) {
        Element cell = cell(sheet, reference);
        return cell == null ? "" : descendantText(cell, "f");
    }

    private static Element cell(Document sheet, String reference) {
        NodeList cells = sheet.getElementsByTagNameNS("*", "c");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            if (reference.equals(cell.getAttribute("r"))) return cell;
        }
        return null;
    }

    private static String descendantText(Node parent, String localName) {
        NodeList nodes = ((Element) parent).getElementsByTagNameNS("*", localName);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.append(nodes.item(index).getTextContent());
        }
        return result.toString();
    }

    private static Document parseXml(String raw) throws Exception {
        assertNotNull(raw, "required OOXML part is missing");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(raw)));
    }

    private static String failureSummary(List<ChatApi.ChatStreamEvent> events) {
        List<String> values = new ArrayList<>();
        events.stream()
                .filter(
                        event ->
                                "error".equals(event.getType())
                                        || "tool_end".equals(event.getType())
                                        || "presentation_created".equals(event.getType()))
                .forEach(
                        event ->
                                values.add(
                                        event.getType()
                                                + ":"
                                                + event.getToolName()
                                                + ":"
                                                + event.getStatus()
                                                + ":"
                                                + event.getText()
                                                + ":"
                                                + event.getDetails()));
        return values.toString();
    }

    private void clearAgentState(String ownerKey, String sessionId) {
        String stateKey =
                storageProperties.getRedisKeyPrefix()
                        + "session:"
                        + ownerKey
                        + "/"
                        + sessionId
                        + ":agent_state";
        try (JedisPooled redis = new JedisPooled(URI.create(storageProperties.getRedisUrl()))) {
            redis.del(stateKey, stateKey + ":ver");
        }
    }

    private void cleanup(String ownerKey, String sessionId) throws Exception {
        if (ownerKey == null) return;
        clearAgentState(ownerKey, sessionId);
        List<String> contentRefs = new ArrayList<>();
        try (Connection connection =
                DriverManager.getConnection(
                        storageProperties.getJdbcUrl(),
                        storageProperties.getJdbcUsername(),
                        storageProperties.getJdbcPassword())) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT content_ref FROM ha_artifact WHERE owner_key = ? AND content_ref IS NOT NULL")) {
                statement.setString(1, ownerKey);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) contentRefs.add(result.getString(1));
                }
            }
            connection.setAutoCommit(false);
            for (String table :
                    List.of(
                            "ha_conversation_history",
                            "ha_interaction",
                            "ha_turn",
                            "ha_session",
                            "ha_artifact")) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM " + table + " WHERE owner_key = ?")) {
                    statement.setString(1, ownerKey);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        }
        BosArtifactContentStore store = new BosArtifactContentStore(artifactProperties.toConfig());
        for (String contentRef : contentRefs) store.delete(contentRef);
    }
}
