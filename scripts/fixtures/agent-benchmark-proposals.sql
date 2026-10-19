select coalesce(json_agg(x),'[]'::json) from (
 select r.project_id,r.id build_run_id,r.user_id build_user_id,m.id request_id,m.author_id request_user_id,
 m.source_proposal_id,m.resolved_request_text,p.id proposal_id,u.author_id proposal_user_id,
 planned.user_id plan_user_id,p.mode proposal_mode,p.status proposal_status,p.proposal_plan_id,
 p.proposal_consumed_by,p.suggested_build_text,p.content
 from agent_runs r
 join messages m on m.id=r.request_message_id and m.project_id=r.project_id and m.role='USER'
 join messages p on p.id=m.source_proposal_id and p.project_id=r.project_id and p.role='ASSISTANT'
 join messages u on u.id=p.reply_to_id and u.project_id=r.project_id and u.role='USER'
 join agent_runs planned on planned.id=p.proposal_plan_id and planned.project_id=r.project_id
  and planned.message_id=p.id and planned.mode='PLAN' and planned.status='DONE'
 where r.project_id in ({{projects}}) and r.mode='BUILD'
) x
