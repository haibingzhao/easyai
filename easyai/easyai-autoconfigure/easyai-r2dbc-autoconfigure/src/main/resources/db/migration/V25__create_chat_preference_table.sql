-- =============================================
-- Per-owner Chat surface preferences
--
-- One row per ownership bucket (personal user id or the group bucket id). A group-owned row
-- applies to every member that has no personal row; the read path resolves self → group.
--
-- default_agent_id  — agent a fresh chat starts with; NULL means the built-in default.
-- hidden_agent_ids  — JSON array of agent ids excluded from the composer's agent picker.
-- =============================================

CREATE TABLE IF NOT EXISTS chat_preference (
    user_id             VARCHAR(255)  NOT NULL,
    default_agent_id    VARCHAR(255),
    hidden_agent_ids    TEXT          NOT NULL DEFAULT '[]',
    created_at          BIGINT        NOT NULL,
    updated_at          BIGINT        NOT NULL,
    PRIMARY KEY (user_id)
);
