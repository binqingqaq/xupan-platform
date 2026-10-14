-- 把旧头像位按序映射到对应的 BY220 头像位，保留当前用户头像占用。
UPDATE sys_user u
JOIN avatar_preset old_preset
  ON old_preset.avatar_key = u.avatar_key
 AND old_preset.display_order BETWEEN 1 AND 40
JOIN avatar_preset by220_preset
  ON by220_preset.display_order = old_preset.display_order + 40
   SET u.avatar_key = by220_preset.avatar_key;

UPDATE avatar_preset
   SET assigned_user_id = NULL,
       assigned_at = NULL,
       updated_at = CURRENT_TIMESTAMP
 WHERE display_order BETWEEN 1 AND 40;

UPDATE avatar_preset by220_preset
JOIN sys_user u ON u.avatar_key = by220_preset.avatar_key
   SET by220_preset.assigned_user_id = u.id,
       by220_preset.assigned_at = COALESCE(by220_preset.assigned_at, CURRENT_TIMESTAMP),
       by220_preset.updated_at = CURRENT_TIMESTAMP
 WHERE by220_preset.display_order BETWEEN 41 AND 80
   AND by220_preset.assigned_user_id IS NULL;

DELETE FROM avatar_preset WHERE display_order BETWEEN 1 AND 40;