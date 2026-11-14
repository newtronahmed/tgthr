-- FEAT-005 follow-up: the operator audit tables already reject row updates and deletes in V12.
-- Add the missing statement-level TRUNCATE guards in a new migration so databases that already
-- applied V12 receive them without a Flyway checksum change.

CREATE TRIGGER operator_auth_events_no_truncate
BEFORE TRUNCATE ON operator_auth_events
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();

CREATE TRIGGER operator_actions_no_truncate
BEFORE TRUNCATE ON operator_actions
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();
