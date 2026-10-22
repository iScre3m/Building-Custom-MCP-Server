package com.example.workbooktools;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

public class WorkbookMcpServer{

    public static void main(String[] args) {
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        var server = McpServer.sync(transportProvider).capabilities(ServerCapabilities.builder().tools(true).build())
        .serverInfo("workbook-tools", "0.1.0")
        .build();

        server.addTool(WorkBookTools.greetingTool());
        server.addTool(WorkBookTools.generateWorkbookTool());
        server.addTool(WorkBookTools.listWorkbooksTool());
        server.addTool(WorkBookTools.clearWorkbooksTool());
    }



}