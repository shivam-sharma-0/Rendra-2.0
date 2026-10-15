package com.code.pal.project.service;

import com.code.pal.project.dto.RequirementDtos;

import java.util.UUID;

public interface RequirementService {
    RequirementDtos.Session get(Long userId, UUID projectId);
    RequirementDtos.Session start(Long userId, UUID projectId);
    RequirementDtos.Session answer(Long userId, UUID projectId, UUID questionId, RequirementDtos.AnswerRequest request);
    RequirementDtos.Session approve(Long userId, UUID projectId);
    RequirementDtos.Session approve(Long userId, UUID projectId, Long revision);
}
