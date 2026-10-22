package com.example.workbooktools;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class WorkBookTools {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("workbook.output.dir", "generated-workbooks"));
    private static final WorkbookGenerator WORKBOOK_GENERATOR = new WorkbookGenerator();

    private record WorkbookResult(String filePath, int rowCount) {

    }

    static SyncToolSpecification greetingTool() {

        String schema = """
                {
                "type": "object",
                "properties": {
                "name": { "type": "string" }
                },
                "required": ["name"]
                }
                """;

        return SyncToolSpecification.builder()
                .tool(Tool.builder("get_greeting", McpJsonDefaults.getMapper(), schema)
                        .description("Returns a greeting for a given name")
                        .build())
                .callHandler((exchange, request) ->

                {
                    String name = (String) request.arguments().get("name");
                    return CallToolResult.builder()
                            .addTextContent("Hello, " + name + "!")
                            .build();
                }).build();
    }

    static SyncToolSpecification generateWorkbookTool() {
        String schema = """
                {
                    "type": "object",
                    "properties": {
                        "fileName": { "type": "string", "description": "Destination file name, e.g. orders.xlsx" },
                        "rowCount": { "type": "integer", "minimum": 1 },
                        "seed": { "type": "integer", "description": "Optional seed for reproducible output" },
                        "columns": {
                            "type": "array",
                            "minItems": 1,
                            "items": {
                                "type": "object",
                                "properties": {
                                    "header": { "type": "string" },
                                    "type": { "type": "string", "enum": ["text", "integer", "decimal", "date", "choice"] },
                                    "words": { "type": "array", "items": { "type": "string" } },
                                    "min": { "type": "number" },
                                    "max": { "type": "number" },
                                    "start": { "type": "string", "description": "ISO-8601 date for date columns" },
                                    "end": { "type": "string", "description": "ISO-8601 date for date columns" },
                                    "options": { "type": "array", "items": { "type": "string" } },
                                    "blankProbability": { "type": "number", "minimum": 0, "maximum": 1 }
                                },
                                "required": ["header", "type"]
                            }
                        }
                    },
                    "required": ["fileName", "rowCount", "columns"]
                }
                """;

        String outputSchema = """
                {
                    "type": "object",
                    "properties": {
                        "filePath": { "type": "string"},
                        "rowCount": { "type": "integer"}
                    },
                    "required": ["filePath", "rowCount"]
                }
                """;

        return SyncToolSpecification.builder().tool(Tool
                .builder("generate_workbook", McpJsonDefaults.getMapper(), schema)
                .description(
                        "Gnerates a sample .xlsx workbook from column specifications and writes it to the output directory")
                .outputSchema(McpJsonDefaults.getMapper(), outputSchema)
                .build())
                .callHandler((exchange, request) -> {
                    // Implementation for generating workbook
                    Map<String, Object> arguments = request.arguments();
                    Path destination;
                    int rowCount;
                    List<ColumnSpec> columns;
                    try {
                        destination = OUTPUT_DIR.resolve(fileNameOf(arguments));
                        rowCount = (int) arguments.get("rowCount");
                        columns = columnsOf(arguments);
                    } catch (RuntimeException e) {
                        return CallToolResult.builder()
                                .addTextContent("Invalid argument: " + e.getMessage())
                                .isError(true)
                                .build();
                    }

                    try {
                        byte[] bytes = arguments.get("seed") instanceof Number seed
                                ? WORKBOOK_GENERATOR.generateSample(columns, rowCount, seed.longValue())
                                : WORKBOOK_GENERATOR.generateSample(columns, rowCount);
                        Files.createDirectories(OUTPUT_DIR);
                        WORKBOOK_GENERATOR.writeToFile(bytes, destination);
                    } catch (IOException e) {
                        // return CallToolResult.builder()
                        //         .addTextContent("Failed to generate workbook: " + e.getMessage())
                        //         .isError(true)
                        //         .build();
                        throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                        .message("Unexpected failure writing workbook: " + e.getMessage())
                        .build();
                    }

                    return CallToolResult.builder()
                            .addTextContent("Wrote " + destination)
                            .structuredContent(new WorkbookResult(destination.toString(), rowCount))
                            .build();
                })
                .build();
    }

    private static String fileNameOf(Map<String, Object> args) {
        String fileName = (String) args.get("fileName");
        return fileName.endsWith(".xlsx") ? fileName : fileName + ".xlsx";
    }

    @SuppressWarnings("unchecked")
    private static List<ColumnSpec> columnsOf(Map<String, Object> args) {
        List<Map<String, Object>> rawColumns = (List<Map<String, Object>>) args.get("columns");
        return rawColumns.stream().map(WorkBookTools::parseColumn).toList();
    }

private static ColumnSpec parseColumn(Map<String, Object> column) {
        String header = (String) column.get("header");
        String type = (String) column.get("type");
        if (header == null || type == null) {
            throw new IllegalArgumentException("Each column requires 'header' and 'type'");
        }
        ColumnSpec spec;
        switch (type) {
            case "text":
                spec = column.containsKey("words")
                        ? ColumnSpec.text(header, stringListOf(column.get("words")))
                        : ColumnSpec.text(header);
                break;
            case "integer":
                requireNumber(column, "min", header);
                requireNumber(column, "max", header);
                spec = ColumnSpec.integer(header, ((Number) column.get("min")).longValue(),
                        ((Number) column.get("max")).longValue());
                break;
            case "decimal":
                requireNumber(column, "min", header);
                requireNumber(column, "max", header);
                spec = ColumnSpec.decimal(header, ((Number) column.get("min")).doubleValue(),
                        ((Number) column.get("max")).doubleValue());
                break;
            case "date":
                spec = ColumnSpec.date(header, LocalDate.parse(requireString(column, "start", header)),
                        LocalDate.parse(requireString(column, "end", header)));
                break;
            case "choice":
                spec = ColumnSpec.choice(header, stringListOf(requireList(column, "options", header)));
                break;
            default:
                throw new IllegalArgumentException("Unknown column type: " + type);
        }
        return column.get("blankProbability") instanceof Number blankProbability
                ? spec.withBlankProbability(blankProbability.doubleValue())
                : spec;
    }

    private static void requireNumber(Map<String, Object> column, String field, String header) {
        if (!(column.get(field) instanceof Number)) {
            throw new IllegalArgumentException(
                    "Column '" + header + "' requires numeric '" + field + "'");
        }
    }

    private static String requireString(Map<String, Object> column, String field, String header) {
        Object value = column.get(field);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "Column '" + header + "' requires string '" + field + "'");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(Map<String, Object> column, String field, String header) {
        Object value = column.get(field);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(
                    "Column '" + header + "' requires array '" + field + "'");
        }
        return (List<Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static List<String> stringListOf(Object value) {
        return ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    static SyncToolSpecification listWorkbooksTool() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder("list_workbooks", McpJsonDefaults.getMapper(), "{\"type\": \"object\"}")
                        .description("Lists the .xlsx workbooks currently in the output directory")
                        .build())
                .callHandler((exchange, request) -> {
                    if (!Files.isDirectory(OUTPUT_DIR)) {
                        return CallToolResult.builder().addTextContent("No workbooks generated yet.").build();
                    }
                    try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
                        List<String> names = files
                                .filter(p -> p.toString().endsWith(".xlsx"))
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList();
                        return CallToolResult.builder()
                                .addTextContent(
                                        names.isEmpty() ? "No workbooks generated yet." : String.join("\n", names))
                                .build();
                    } catch (IOException e) {
                        throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                        .message("Unexpected failure listing workbooks: " + e.getMessage())
                        .build();
                    }
                })
                .build();
    }

    static SyncToolSpecification clearWorkbooksTool() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder("clear_workbooks", McpJsonDefaults.getMapper(), "{\"type\": \"object\"}")
                        .description("Deletes all workbooks from the output directory")
                        .build())
                .callHandler((exchange, request) -> {
                    if (!Files.isDirectory(OUTPUT_DIR)) {
                        return CallToolResult.builder().addTextContent("Nothing to clear.").build();
                    }
                    try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
                        List<Path> toDelete = files.filter(p -> p.toString().endsWith(".xlsx")).toList();
                        for (Path path : toDelete) {
                            Files.delete(path);
                        }
                        return CallToolResult.builder()
                                .addTextContent("Deleted " + toDelete.size() + " workbook(s).")
                                .build();
                    } catch (IOException e) {
                        throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                        .message("Unexpected failure clearing workbooks: " + e.getMessage())
                        .build();
                    }
                })
                .build();
    }
}
