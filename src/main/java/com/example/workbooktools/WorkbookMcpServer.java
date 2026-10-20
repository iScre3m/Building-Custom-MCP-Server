package com.example.workbooktools;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

public class WorkbookMcpServer{

    public static void main(String[] args) {
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        McpServer.sync(transportProvider).serverInfo("workbook-tools", "0.1.0").build();
    }

}