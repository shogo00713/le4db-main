-- =====================================================
-- シェアサイクル事業者
-- =====================================================
INSERT INTO share_bike_operator (operator_id, operator_name, operator_contact)
VALUES
  (1, 'HAPPY CYCLING', '09001234567'),
  (2, 'HELLO CYCLING', '09076543210');


-- =====================================================
-- ポート情報
-- =====================================================
INSERT INTO port_information (port_id, port_name, port_latitude, port_longitude, capacity)
VALUES
-- HAPPY CYCLING 用ポート
  ( 1, '京都駅前ポート',      34.9865818, 135.761337, 20),
  ( 2, '四条河原町ポート',    35.003827, 135.770583, 10),
  ( 3, '四条烏丸ポート',      35.004317, 135.759741, 10),
  ( 4, '京都市役所前ポート',  35.011534, 135.769002, 10),
  ( 5, '北大路BTポート',      35.04762, 135.758400, 10),
  ( 6, '出町柳ポート',        35.030538, 135.773669, 5),
  ( 7, '銀閣寺道ポート',      35.027253, 135.794598, 10),
  ( 8, '金閣寺道ポート',      35.039248, 135.731839, 10),
  ( 9, '二条城前ポート',      35.014722, 135.751636, 10),
  (10, '嵐山ポート',         35.017626, 135.676638, 10),
  (11, '東山三条ポート',     35.009639, 135.778066, 5),
  (12, '東福寺ポート',       34.975347, 135.773298, 10),
  (13, '九条ポート',         34.979631, 135.760712, 5),
  (14, '烏丸今出川ポート',   35.029089, 135.759442, 10),
  (15, '西院ポート',         35.004339, 135.732821, 5),
-- HELLO CYCLING 用ポート
  (16, '京都駅前ポート',     34.984030, 135.758081, 10),
  (17, '四条河原町ポート',    35.003079, 135.769280, 15),
  (18, '三条京阪ポート',      35.009069, 135.773547, 10),
  (19, '京都市役所前ポート',  35.010118, 135.769050, 5),
  (20, '百万遍ポート',        35.029015, 135.778493, 10),
  (21, '出町柳ポート',       35.028811, 135.773711, 10),
  (22, '銀閣寺道ポート',      35.027544, 135.791654, 5),
  (23, '東山駅前ポート',      35.009060, 135.779678, 10),
  (24, '二条城前ポート',      35.011747, 135.750187, 10),
  (25, '嵐山ポート',         35.010967, 135.677780, 10),
  (26, '清水口ポート',       34.996625, 135.776777, 10),
  (27, '円町ポート',         35.017797, 135.731741, 5),
  (28, '烏丸御池ポート',     35.011616, 135.759807, 10),
  (29, '烏丸今出川ポート',    35.029165, 135.759485, 5),
  (30, '西院ポート',          35.003567, 135.731970, 10);


-- =====================================================
-- ポート運営
-- =====================================================
INSERT INTO port_operation (port_id, operator_id)
VALUES 
-- HAPPY CYCLING
  ( 1, 1), ( 2, 1), ( 3, 1), ( 4, 1), ( 5, 1),
  ( 6, 1), ( 7, 1), ( 8, 1), ( 9, 1), (10, 1),
  (11, 1), (12, 1),(13, 1), (14, 1), (15, 1),
-- HELLO CYCLING
  (16, 2), (17, 2), (18, 2), (19, 2), (20, 2),
  (21, 2), (22, 2), (23, 2), (24, 2), (25, 2),
  (26, 2), (27, 2), (28, 2), (29, 2), (30, 2);


-- =====================================================
-- 自転車情報
-- =====================================================
-- HAPPY(1): 初期として 60台を port 1..15 に配置（docked）
INSERT INTO share_bike(status, updated_at)
SELECT 'docked', CURRENT_TIMESTAMP
FROM generate_series(1, 60) g;

-- HAPPY(1): bike_management に登録（bike_id 1-60）
WITH happy_bikes AS (
  SELECT bike_id FROM share_bike WHERE bike_id <= 60 ORDER BY bike_id
)
INSERT INTO bike_management(bike_id, operator_id)
SELECT bike_id, 1 FROM happy_bikes;

-- HAPPY(1): bike_parking に登録（ポート1-15に配置）
WITH happy_bikes AS (
  SELECT ROW_NUMBER() OVER (ORDER BY bike_id ASC) AS seq, bike_id
  FROM share_bike WHERE bike_id <= 60
)
INSERT INTO bike_parking(bike_id, operator_id, current_port_id, parked_at)
SELECT bike_id, 1, ((seq - 1) % 15) + 1, CURRENT_TIMESTAMP
FROM happy_bikes;

-- HELLO(2): 初期として 60台を port 16..30 に配置（docked）
INSERT INTO share_bike(status, updated_at)
SELECT 'docked', CURRENT_TIMESTAMP
FROM generate_series(1, 60) g;

-- HELLO(2): bike_management に登録（bike_id 61以上）
WITH hello_bikes AS (
  SELECT bike_id FROM share_bike WHERE bike_id > 60 ORDER BY bike_id
)
INSERT INTO bike_management(bike_id, operator_id)
SELECT bike_id, 2 FROM hello_bikes;

-- HELLO(2): bike_parking に登録（ポート16-30に配置）
WITH hello_bikes AS (
  SELECT ROW_NUMBER() OVER (ORDER BY bike_id ASC) AS seq, bike_id
  FROM share_bike WHERE bike_id > 60
)
INSERT INTO bike_parking(bike_id, operator_id, current_port_id, parked_at)
SELECT bike_id, 2, 15 + ((seq - 1) % 15) + 1, CURRENT_TIMESTAMP
FROM hello_bikes;