package com.code.pal.project.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequirementInterviewPolicyTest {
    @Test
    void genericUseCaseAndBareMinimumQuestionsAreNotProductBlockers() {
        assertTrue(RequirementInterviewPolicy.redundant("primary_use_case", "What is the primary use case for the Todo application?"));
        assertTrue(RequirementInterviewPolicy.redundant("scope", "How complete should the first version be?"));
        assertTrue(RequirementInterviewPolicy.redundant("task_features", "Beyond adding and completing tasks, which features matter?"));
        assertFalse(RequirementInterviewPolicy.redundant("booking_rules", "Can appointments overlap for the same team member?"));
    }
}
