-- Create currency_series table for managing supported currencies and their provider series IDs
--
-- This table stores the mapping between ISO 4217 currency codes and their corresponding
-- exchange rate provider series identifiers. This enables dynamic currency
-- support instead of hardcoding currency/series mappings in the application code.
--
-- Table structure:
--   id: Primary key, auto-generated
--   currency_code: ISO 4217 three-letter currency code (e.g., 'EUR', 'JPY', 'THB')
--   provider_series_id: Exchange rate provider series identifier (e.g., 'DEXUSEU' for EUR, 'DEXTHUS' for THB)
--   enabled: Whether this currency is actively supported for exchange rate imports
--   created_at: Timestamp when the record was created
--   updated_at: Timestamp when the record was last modified
--
-- Constraints:
--   - currency_code must be unique (one provider series per currency)
--   - provider_series_id cannot be null (required for API integration)
--   - enabled defaults to true
--
-- Indexes:
--   - currency_code: Unique constraint for one currency per record
--   - provider_series_id: For reverse lookups from provider series to currency
--   - enabled: For filtering active currencies

CREATE TABLE currency_series (
    id BIGSERIAL PRIMARY KEY,
    currency_code VARCHAR(3) NOT NULL UNIQUE,
    provider_series_id VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for reverse lookups by provider series ID
CREATE INDEX idx_currency_series_provider_series_id
    ON currency_series (provider_series_id);

-- Index for filtering enabled currencies
-- Partial index matches query pattern (findByEnabledTrue) and avoids inefficient boolean index
CREATE INDEX idx_currency_series_enabled
    ON currency_series (enabled)
    WHERE enabled = true;

-- Table and column comments for documentation
COMMENT ON TABLE currency_series IS 'Mapping between ISO 4217 currency codes and exchange rate provider series identifiers';
COMMENT ON COLUMN currency_series.id IS 'Primary key, auto-generated';
COMMENT ON COLUMN currency_series.currency_code IS 'ISO 4217 three-letter currency code (e.g., EUR, JPY, THB)';
COMMENT ON COLUMN currency_series.provider_series_id IS 'Exchange rate provider series identifier (e.g., DEXUSEU for EUR/USD, DEXTHUS for THB/USD)';
COMMENT ON COLUMN currency_series.enabled IS 'Whether this currency is actively supported for exchange rate imports';
COMMENT ON COLUMN currency_series.created_at IS 'Record creation timestamp';
COMMENT ON COLUMN currency_series.updated_at IS 'Record last modification timestamp';
