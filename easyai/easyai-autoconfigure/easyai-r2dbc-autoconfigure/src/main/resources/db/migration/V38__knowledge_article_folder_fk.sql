-- =============================================
-- Folder membership becomes a real reference
--
-- knowledge_article.folder_id was a plain column, so a folder deleted concurrently with an upload
-- or a move could leave an article row pointing at nothing: invisible in the tree, unreachable
-- through the folder API, and impossible to clean up. The constraint makes the losing writer fail
-- instead of persisting a dangling row.
--
-- Additive; V36 created both tables together, so there is no backfill.
-- =============================================

ALTER TABLE knowledge_article ADD CONSTRAINT fk_ka_folder
    FOREIGN KEY (folder_id) REFERENCES knowledge_folder (id);
