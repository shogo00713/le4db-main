-- =====================================================
-- 管理者権限テーブル
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_credentials (
    operator_id   INTEGER,
    operator_name VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

-- < 外部キー >
    PRIMARY KEY (operator_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id)
);

-- =====================================================
-- 管理者ビュー
-- =====================================================
CREATE OR REPLACE VIEW v_admin_credentials AS
SELECT
    a.operator_id,
    COALESCE(o.operator_name, a.operator_name) AS operator_name,
    o.operator_contact,
    a.password,
    a.created_at
FROM admin_credentials a
LEFT JOIN share_bike_operator o USING (operator_id);

-- =====================================================
-- 初期管理者アカウントの挿入例
-- =====================================================
INSERT INTO admin_credentials (operator_id, operator_name, password) 
SELECT operator_id, operator_name, 'admin123' 
FROM share_bike_operator
ON CONFLICT (operator_id) DO NOTHING;


UPDATE admin_credentials SET password = 'happyworld' WHERE operator_id = 1;
UPDATE admin_credentials SET password = 'helloworld' WHERE operator_id = 2;
