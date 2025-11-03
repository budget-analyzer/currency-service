-- Rollback migration for V2__add_shedlock_table.sql
--
-- NOTE: This is for documentation purposes only. Flyway community edition
-- does not automatically execute undo migrations. If you need to rollback,
-- manually execute this script.

DROP TABLE IF EXISTS shedlock;
