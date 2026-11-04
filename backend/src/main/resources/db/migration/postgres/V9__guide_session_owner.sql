ALTER TABLE guide_session ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

UPDATE guide_session session
   SET user_id = request.owner_id
  FROM trip_plan plan
  JOIN trip_request request ON request.id = plan.request_id
 WHERE session.plan_id = plan.id
   AND session.user_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_guide_session_user
    ON guide_session (user_id);
