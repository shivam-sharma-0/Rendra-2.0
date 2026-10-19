package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient.Usage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Optional monetary estimates from versioned operator prices; never an invoice or quota authority. */
@Component
public final class ModelCallPricing {
    public record Estimate(BigDecimal amountUsd, String kind, String revision) {}
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private final AgentProperties properties;

    public ModelCallPricing(AgentProperties properties) {
        this.properties = properties;
    }

    public boolean configured() {
        return !properties.getModelPricing().isEmpty();
    }

    public Estimate estimate(String provider, String model, Usage usage) {
        var price = properties.getModelPricing().get(provider + ":" + model);
        if (usage == null || usage.promptTokens() < 0 || usage.completionTokens() < 0 || price == null
                || price.getRevision() == null || price.getRevision().isBlank() || price.getRevision().length() > 80
                || !valid(price.getInputPerMillion()) || !valid(price.getOutputPerMillion())) return null;
        Integer cached = usage.cachedInputTokens();
        boolean detailed = cached != null && valid(price.getCachedInputPerMillion());
        BigDecimal input = price.getInputPerMillion().multiply(BigDecimal.valueOf(usage.promptTokens()));
        if (detailed) {
            input = price.getInputPerMillion().multiply(BigDecimal.valueOf(usage.promptTokens() - cached))
                    .add(price.getCachedInputPerMillion().multiply(BigDecimal.valueOf(cached)));
        }
        // Reasoning is a subset of reported completion tokens; adding it again would double-count it.
        BigDecimal total = input.add(price.getOutputPerMillion().multiply(BigDecimal.valueOf(usage.completionTokens())))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        return new Estimate(total, detailed ? "REPORTED_USAGE" : "UNDISCOUNTED_ESTIMATE", price.getRevision());
    }

    private static boolean valid(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.precision() <= 12 && value.scale() >= 0;
    }
}
