-- =====================================================
-- アカウント
-- =====================================================
CREATE TABLE accounts (
    account_id    INTEGER,
    account_name  VARCHAR(30) NOT NULL,
    password      VARCHAR(255) NOT NULL,

-- < 主キー >
    PRIMARY KEY (account_id)
);

-- =====================================================
-- アカウント対応
-- =====================================================
CREATE TABLE bike_operator_accounts (
    operator_id INTEGER,
    account_id  INTEGER,

-- < 主キー & 外部キー >
    PRIMARY KEY (operator_id, account_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);



-- =====================================================
-- アカウント管理ビュー（運営者 × アカウント）
-- =====================================================
CREATE OR REPLACE VIEW v_operator_accounts AS
SELECT
    o.operator_id,
    o.operator_name,
    o.operator_contact,
    a.account_id,
    a.account_name,
    a.password
FROM share_bike_operator o
LEFT JOIN bike_operator_accounts boa ON boa.operator_id = o.operator_id
LEFT JOIN accounts a ON a.account_id = boa.account_id;



-- =====================================================
-- データ例：アカウント
-- =====================================================
INSERT INTO accounts (account_id, account_name, password)
VALUES
  (1, 'HAPPY_ADMIN_1', 'happyworld'),
  (2, 'HELLO_ADMIN_1', 'helloworld');


-- =====================================================
-- データ例：アカウント対応
-- =====================================================
INSERT INTO bike_operator_accounts (operator_id, account_id)
VALUES
  (1, 1),  -- HAPPY CYCLING の管理者
  (2, 2);  -- HELLO CYCLING の管理者
