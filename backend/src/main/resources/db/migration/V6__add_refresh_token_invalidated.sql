-- Add invalidated column for refresh token rotation / reuse detection
ALTER TABLE refresh_tokens ADD COLUMN invalidated BOOLEAN NOT NULL DEFAULT false;
