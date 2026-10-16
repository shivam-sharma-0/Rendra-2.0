package com.code.pal.agent.contract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

/** Canonical identity, independent of JVM map/set iteration. Ordered lists keep their semantics. */
public final class ContractIdentity {
    private ContractIdentity() {
    }

    public static String hash(BuildContract contract, ObjectMapper mapper) {
        var node = (ObjectNode) mapper.valueToTree(contract);
        node.set("envNames", mapper.valueToTree(new TreeSet<>(contract.envNames())));
        return hashValue(node, mapper);
    }

    /** Object keys are sorted recursively. Callers must represent unordered sets as sorted collections. */
    public static String hashValue(Object value, ObjectMapper mapper) {
        return ContractStore.hash(mapper.writeValueAsString(sorted(mapper.valueToTree(value))));
    }

    private static Object sorted(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false).map(ContractIdentity::sorted).toList();
        }
        return node;
    }
}
