package io.quarkus.test.common;

import java.util.HashMap;
import java.util.Map;

public class ListeningAddresses {
    private final Map<String, ListeningAddress> serviceMap;

    public ListeningAddresses(Map<String, ListeningAddress> serviceMap) {
        this.serviceMap = new HashMap<>(serviceMap);
    }

    public ListeningAddress getService(String service) {
        return serviceMap.get(service);
    }

    public static class ListeningAddress {
        private final int port;
        private final String protocol;

        public ListeningAddress(int port, String protocol) {
            this.port = port;
            this.protocol = protocol;
        }

        public int getPort() {
            return port;
        }

        public String getProtocol() {
            return protocol;
        }

        public boolean isSsl() {
            return "https".equals(protocol);
        }
    }
}
