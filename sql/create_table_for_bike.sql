-- =====================================================
-- シェアサイクル事業者
-- =====================================================
CREATE TABLE share_bike_operator (
    operator_id       SERIAL,               -- 事業者ID (主キー)
    operator_name     VARCHAR(30) NOT NULL, -- 事業者名
    operator_contact  VARCHAR(11) NOT NULL, -- 事業者連絡先

-- < 主キー >
    PRIMARY KEY (operator_id)
);


-- =====================================================
-- ポート情報
-- =====================================================
CREATE TABLE port_information (
    port_id        SERIAL,                    -- ポートID (主キー)
    port_name      VARCHAR(30) NOT NULL,      -- ポート名
    port_latitude  DOUBLE PRECISION NOT NULL, -- 緯度
    port_longitude DOUBLE PRECISION NOT NULL, -- 経度
    capacity       INTEGER NOT NULL,          -- 収容台数,

-- < 主キー >
    PRIMARY KEY (port_id),

-- < 制約 >
    CONSTRAINT port_capacity_nonneg CHECK (capacity >= 0)
);


-- =====================================================
-- ポート運営 (シェアサイクル事業者 × ポート情報)
-- =====================================================
CREATE TABLE port_operation (
    operator_id INTEGER NOT NULL, -- 事業者ID (主キー)
    port_id     INTEGER NOT NULL, -- ポートID (主キー)

-- < 主キー & 外部キー >
    PRIMARY KEY (port_id),    
    FOREIGN KEY (port_id) REFERENCES port_information(port_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
    UNIQUE (operator_id, port_id) -- 事業者を跨いだポートIDの重複を防止
);


-- =====================================================
-- 自転車情報
-- =====================================================
CREATE TABLE share_bike (
    bike_id          SERIAL,                                       -- 自転車ID (主キー)
    operator_id      INTEGER NOT NULL,                             -- 事業者ID
    current_port_id  INTEGER NULL,                                 -- 現在のポートID (貸出中はNULL)
    status           VARCHAR(15) NOT NULL,                         -- 状態 (docked, rented, maintenance)
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 最終更新日時

-- < 主キー & 外部キー >
    PRIMARY KEY (bike_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
    FOREIGN KEY (operator_id, current_port_id) REFERENCES port_operation(operator_id, port_id),
    UNIQUE (bike_id, operator_id), -- 事業者を跨いだ自転車IDの重複を防止

-- < 制約 >
    CONSTRAINT bike_status_check CHECK (status IN ('docked', 'rented', 'maintenance')),
    CONSTRAINT bike_state_check CHECK (
      (status = 'docked' AND current_port_id IS NOT NULL) OR -- docked   => current_port_id NOT NULL
      (status = 'rented' AND current_port_id IS NULL) OR     -- rented   => current_port_id IS NULL
      (status = 'maintenance')                               -- maintenance は NULL/NOT NULL どちらも許す
    )
);


-- =====================================================
-- 貸出/返却履歴
-- =====================================================
CREATE TABLE share_bike_rental (
  rental_id      SERIAL,                                       -- レンタルID (主キー)
  bike_id        INTEGER NOT NULL,                             -- 自転車ID
  operator_id    INTEGER NOT NULL,                             -- 事業者ID
  start_port_id  INTEGER NOT NULL,                             -- 貸出ポートID
  end_port_id    INTEGER NULL,                                 -- 返却ポートID
  start_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 貸出日時
  end_time       TIMESTAMP NULL,                               -- 返却日時

-- < 主キー & 外部キー >
    PRIMARY KEY (rental_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
    FOREIGN KEY (bike_id, operator_id) REFERENCES share_bike(bike_id, operator_id),
    FOREIGN KEY (operator_id, start_port_id) REFERENCES port_operation(operator_id, port_id),
    FOREIGN KEY (operator_id, end_port_id) REFERENCES port_operation(operator_id, port_id)
);


-- =====================================================
-- 自転車移動ログ (シェアサイクル事業者 x ポート情報 x ポート情報)
-- =====================================================
CREATE TABLE IF NOT EXISTS bike_move_log (
  log_id        bigserial,                                -- ログID (主キー),
  operator_id   integer NOT NULL,                         -- 事業者ID,
  from_port_id  integer NOT NULL,                         -- 移動元ポートID,
  to_port_id    integer NOT NULL,                         -- 移動先ポートID,
  moved_bikes   integer NOT NULL CHECK (moved_bikes > 0), -- 移動台数,
  moved_at      timestamp NOT NULL DEFAULT now(),          -- 移動日時,
  source        varchar(10) NOT NULL DEFAULT 'admin',      -- 生成種別 (admin/user)
  
-- < 主キー & 外部キー >
  PRIMARY KEY (log_id),
  FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
  FOREIGN KEY (from_port_id) REFERENCES port_information(port_id),
  FOREIGN KEY (to_port_id) REFERENCES port_information(port_id),

-- < 制約 >
  CONSTRAINT different_ports CHECK (from_port_id <> to_port_id)
);


-- =====================================================
-- シェアサイクル予約管理 (ユーザー予約・利用フロー)
-- =====================================================
CREATE TABLE IF NOT EXISTS share_bike_reservation (
  reservation_id    BIGSERIAL PRIMARY KEY,                          -- 予約ID
  bike_id           INTEGER NOT NULL,                               -- 自転車ID
  operator_id       INTEGER NOT NULL,                               -- 事業者ID
  status            VARCHAR(15) NOT NULL DEFAULT 'reserved',        -- 状態 (reserved, in_use, returned)
  reserved_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 予約日時
  started_at        TIMESTAMP NULL,                                 -- 利用開始日時
  returned_at       TIMESTAMP NULL,                                 -- 返却日時
  start_port_id     INTEGER NULL,                                   -- 借りたポートID
  end_port_id       INTEGER NULL,                                   -- 返却ポートID
  
-- < 外部キー >
  FOREIGN KEY (bike_id, operator_id) REFERENCES share_bike(bike_id, operator_id),
  FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
  FOREIGN KEY (operator_id, start_port_id) REFERENCES port_operation(operator_id, port_id),
  FOREIGN KEY (operator_id, end_port_id) REFERENCES port_operation(operator_id, port_id),

-- < 制約 >
  CONSTRAINT bike_reservation_status_check CHECK (status IN ('reserved', 'in_use', 'returned')),
  CONSTRAINT bike_reservation_time_check CHECK (
    (status = 'reserved' AND started_at IS NULL AND returned_at IS NULL) OR
    (status = 'in_use' AND started_at IS NOT NULL AND returned_at IS NULL) OR
    (status = 'returned' AND started_at IS NOT NULL AND returned_at IS NOT NULL)
  )
);


-- =====================================================
-- ポート状況ビュー (ポート情報 × ポート運営 × シェアサイクル事業者 × 自転車情報)
-- =====================================================
CREATE VIEW port_status AS
SELECT
    p.port_id,
    po.operator_id,
    o.operator_name,
    o.operator_contact,
    p.port_name,
    p.port_latitude,
    p.port_longitude,
    p.capacity,
    COUNT(b.bike_id) FILTER (WHERE b.status='docked') AS bikes,
    p.capacity - COUNT(b.bike_id) FILTER (WHERE b.status='docked') AS free_docks
FROM port_information p
JOIN port_operation po ON po.port_id = p.port_id
JOIN share_bike_operator o ON o.operator_id = po.operator_id
LEFT JOIN share_bike b ON b.current_port_id = p.port_id AND b.operator_id = po.operator_id
GROUP BY p.port_id, po.operator_id, o.operator_name, o.operator_contact, p.port_name, p.port_latitude, p.port_longitude, p.capacity;


-- =====================================================
-- 索引
-- =====================================================

-- ポートの緯度経度検索
CREATE INDEX port_latlon_idx ON port_information(port_latitude, port_longitude);

-- ポート運営の事業者検索
CREATE INDEX IF NOT EXISTS port_operation_operator_idx ON port_operation(operator_id);

-- 自転車情報の事業者・現在のポート・状態検索
CREATE INDEX share_bike_operator_idx ON share_bike(operator_id);
CREATE INDEX share_bike_port_idx     ON share_bike(current_port_id);
CREATE INDEX share_bike_status_idx   ON share_bike(status);

-- 自転車貸出/返却履歴の事業者・自転車・貸出中検索
CREATE UNIQUE INDEX rental_active_idx ON share_bike_rental(bike_id) WHERE end_time IS NULL;

-- 自転車移動ログの事業者・移動日時検索
CREATE INDEX IF NOT EXISTS bike_move_log_op_movedat_idx ON bike_move_log (operator_id, moved_at DESC);
CREATE INDEX IF NOT EXISTS bike_move_log_movedat_idx ON bike_move_log (moved_at DESC);

-- 自転車移動ログの移動元・移動先ポート検索
CREATE INDEX IF NOT EXISTS bike_move_log_from_idx ON bike_move_log (from_port_id);
CREATE INDEX IF NOT EXISTS bike_move_log_to_idx ON bike_move_log (to_port_id);

-- シェアサイクル予約の状態検索
CREATE INDEX IF NOT EXISTS bike_reservation_status_idx ON share_bike_reservation(status);

-- シェアサイクル予約の自転車検索
CREATE INDEX IF NOT EXISTS bike_reservation_bike_idx ON share_bike_reservation(bike_id, operator_id);

-- シェアサイクル予約の予約日時検索
CREATE INDEX IF NOT EXISTS bike_reservation_reserved_at_idx ON share_bike_reservation(reserved_at DESC);

-- =====================================================
-- 削除禁止トリガー（bike_move_log）
-- =====================================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_proc WHERE proname = 'forbid_delete_bike_move_log'
  ) THEN
    CREATE FUNCTION forbid_delete_bike_move_log() RETURNS trigger AS $$
    BEGIN
      RAISE EXCEPTION 'bike_move_log cannot be deleted';
      RETURN NULL;
    END;
    $$ LANGUAGE plpgsql;
  END IF;
END $$;

DROP TRIGGER IF EXISTS trg_forbid_delete_bike_move_log ON bike_move_log;
CREATE TRIGGER trg_forbid_delete_bike_move_log
  BEFORE DELETE ON bike_move_log
  FOR EACH ROW EXECUTE FUNCTION forbid_delete_bike_move_log();