-- Create ShedLock table for distributed scheduled task coordination
--
-- This table is used by ShedLock to ensure that scheduled tasks run only once
-- across multiple application instances in a distributed environment.
--
-- Table structure:
--   name: Unique identifier for the scheduled task (e.g., "exchangeRateImport")
--   lock_until: Timestamp until which the lock is valid
--   locked_at: Timestamp when the lock was acquired
--   locked_by: Identifier of the instance that holds the lock (hostname + thread)
--
-- ShedLock automatically manages this table - no manual intervention required.

CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

COMMENT ON TABLE shedlock IS 'ShedLock distributed lock coordination table';
COMMENT ON COLUMN shedlock.name IS 'Unique lock name (e.g., exchangeRateImport)';
COMMENT ON COLUMN shedlock.lock_until IS 'Lock expiration timestamp';
COMMENT ON COLUMN shedlock.locked_at IS 'Lock acquisition timestamp';
COMMENT ON COLUMN shedlock.locked_by IS 'Lock holder identifier (hostname + thread)';
