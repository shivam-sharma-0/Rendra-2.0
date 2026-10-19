package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ModelCallPricingTest {
    @Test
    void cachedInputIsDiscountedAndReasoningIsNotCountedTwice() {
        var properties = properties();
        var result = new ModelCallPricing(properties).estimate("fixture", "model", new Usage(12, 3, 8, 2));
        assertEquals(new BigDecimal("0.000024000000"), result.amountUsd());
        assertEquals("fixture-revision", result.revision());
        assertEquals("REPORTED_USAGE", result.kind());
    }

    @Test
    void missingCachedDetailIsAnExplicitUndiscountedEstimate() {
        var result = new ModelCallPricing(properties()).estimate("fixture", "model", new Usage(12, 3));
        assertEquals(new BigDecimal("0.000036000000"), result.amountUsd());
        assertEquals("UNDISCOUNTED_ESTIMATE", result.kind());
    }

    @Test
    void absentPricesAndUnknownUsageNeverBecomeFree() {
        var pricing = new ModelCallPricing(new AgentProperties());
        assertNull(pricing.estimate("fixture", "model", new Usage(12, 3)));
        assertNull(new ModelCallPricing(properties()).estimate("fixture", "model", null));
        assertNull(new ModelCallPricing(properties()).estimate("fixture", "model", new Usage(-1, 3)));
        var properties = properties();
        properties.getModelPricing().get("fixture:model").setRevision("");
        assertNull(new ModelCallPricing(properties).estimate("fixture", "model", new Usage(12, 3)));
    }

    @Test
    void invalidOptionalDetailsAreUnknownWithoutChangingReportedTotals() {
        assertEquals(new Usage(12, 3), new Usage(12, 3, 13, -1));
    }

    private AgentProperties properties() {
        var properties = new AgentProperties();
        var price = new AgentProperties.ModelPrice();
        price.setRevision("fixture-revision");
        price.setInputPerMillion(new BigDecimal("2"));
        price.setCachedInputPerMillion(new BigDecimal("0.5"));
        price.setOutputPerMillion(new BigDecimal("4"));
        properties.getModelPricing().put("fixture:model", price);
        return properties;
    }
}
