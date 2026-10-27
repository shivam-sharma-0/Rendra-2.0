package com.code.pal.agent.contract;

/** Stable validator rule/location, distinct from rejected model values and diagnostic wording. */
final class ContractRuleException extends IllegalArgumentException {
    private static final String SAFE_DETAIL = "[\\p{L}\\p{N} _./:,;()\\[\\]{}$-]{1,600}";
    private final String signature;

    ContractRuleException(String field, String unit, String rule) {
        super(field + "/" + unit + ": " + rule);
        this.signature = getMessage();
    }

    /** Value-free rule signature; the correction feedback also names the validated value when it is bounded text. */
    ContractRuleException(String rule, String detail) {
        super(detail != null && detail.matches(SAFE_DETAIL) ? rule + "; " + detail : rule);
        this.signature = rule;
    }

    String signature() { return signature; }
}
