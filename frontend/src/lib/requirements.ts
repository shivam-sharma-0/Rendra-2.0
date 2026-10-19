import type { RequirementSession } from '../api/projects'

/** Selected platform decisions are setup data; unanswered capability conflicts still need attention. */
export function interviewQuestions(session: RequirementSession) {
  return session.questions.filter((question) => !question.key.startsWith('platform_') || (!question.selectedOption && !question.otherText))
}

export function hasUnansweredSetup(session: RequirementSession) {
  return session.questions.some((question) => question.key.startsWith('platform_') && question.required && !question.selectedOption && !question.otherText)
}
