package com.datagenerator.ai.port;

import java.util.List;

public interface ConnectionCatalogPort {

    List<ConnectionInfo> listConnections();

    record ConnectionInfo(String name, String type) {}
}
