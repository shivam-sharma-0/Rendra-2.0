import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { requirementsApi, type Project, type RequirementSession } from '../../api/projects'
import { needsRequirementInterview } from './runUtils'

/**
 * The requirements interview for a project in the REQUIREMENTS stage: starts it once, records
 * answers, and approves (or fills in the recommended answers and approves). `onApproved` runs
 * after approval so the page can switch its layout.
 */
export function useRequirements(id: string, project: Project | null, setProject: Dispatch<SetStateAction<Project | null>>, onApproved: () => void) {
  const [requirements, setRequirements] = useState<RequirementSession | null>(null)
  const [requirementsBusy, setRequirementsBusy] = useState(false)
  const [requirementsError, setRequirementsError] = useState('')
  const requirementsStarted = useRef<string | null>(null)

  useEffect(() => {
    if (!project || project.lifecycleStage !== 'REQUIREMENTS' || !needsRequirementInterview(project) || requirementsStarted.current === id) return
    requirementsStarted.current = id
    setRequirementsError('')
    void requirementsApi.start(id).then(setRequirements).catch((error) => {
      setRequirementsError(error instanceof Error ? error.message : 'Could not start requirements interview.')
    })
  }, [id, project])

  const answerRequirement = useCallback(async (questionId: string, selectedOption: string | null, otherText: string | null) => {
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      setRequirements(await requirementsApi.answer(id, questionId, { selectedOption, otherText }))
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not save this answer.')
      throw error
    } finally {
      setRequirementsBusy(false)
    }
  }, [id])

  const approveRequirements = useCallback(async () => {
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      setRequirements(await requirementsApi.approve(id))
      setProject((current) => current ? { ...current, lifecycleStage: 'DESIGN' } : current)
      onApproved()
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not approve requirements.')
    } finally {
      setRequirementsBusy(false)
    }
  }, [id, setProject, onApproved])

  const skipRequirements = useCallback(async () => {
    if (!requirements) return
    setRequirementsBusy(true)
    setRequirementsError('')
    try {
      let latest = requirements
      for (const question of requirements.questions) {
        if (!question.required || question.selectedOption || question.otherText) continue
        const recommended = question.recommendedOption
          ?? question.options.find((option) => option.recommended)?.id
          ?? question.options[0]?.id
        if (!recommended) continue
        latest = await requirementsApi.answer(id, question.id, { selectedOption: recommended, otherText: null })
      }
      setRequirements(latest)
      if (latest.complete) {
        const approved = await requirementsApi.approve(id)
        setRequirements(approved)
        setProject((current) => current ? { ...current, lifecycleStage: 'DESIGN' } : current)
        onApproved()
      }
    } catch (error) {
      setRequirementsError(error instanceof Error ? error.message : 'Could not use the recommended answers.')
    } finally {
      setRequirementsBusy(false)
    }
  }, [id, requirements, setProject, onApproved])

  return { requirements, requirementsBusy, requirementsError, answerRequirement, approveRequirements, skipRequirements }
}
