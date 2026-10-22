package com.code.pal.agent.contract;

/** Stable validator rule/location, distinct from rejected model values and diagnostic wording. */
final class ContractRuleException extends IllegalArgumentException {
    private final String signature;

    ContractRuleException(String field, String unit, String rule) {
        super(field + "/" + unit + ": " + rule);
        this.signature = getMessage();
    }

    String signature() { return signature; }
}
