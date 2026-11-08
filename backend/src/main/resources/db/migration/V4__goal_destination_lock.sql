-- FEAT-003 hardening: the payout destination is immutable once locked. Closing a goal only
-- changes status/timestamps, so the trigger allows the normal close path.

CREATE FUNCTION protect_locked_goal_destination() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.destination_locked_at IS NOT NULL AND (
        NEW.destination_type IS DISTINCT FROM OLD.destination_type
        OR NEW.destination_phone_ciphertext IS DISTINCT FROM OLD.destination_phone_ciphertext
        OR NEW.destination_phone_hash IS DISTINCT FROM OLD.destination_phone_hash
        OR NEW.destination_network IS DISTINCT FROM OLD.destination_network
        OR NEW.destination_account_name IS DISTINCT FROM OLD.destination_account_name
        OR NEW.destination_locked_at IS DISTINCT FROM OLD.destination_locked_at
    ) THEN
        RAISE EXCEPTION 'goal destination is locked'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER goals_destination_locked
BEFORE UPDATE ON goals
FOR EACH ROW EXECUTE FUNCTION protect_locked_goal_destination();

CREATE INDEX idx_otp_challenges_retention
    ON otp_challenges ((coalesce(consumed_at, expires_at)));

CREATE INDEX idx_user_sessions_retention
    ON user_sessions ((coalesce(revoked_at, expires_at)));
