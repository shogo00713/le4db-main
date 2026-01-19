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
    port_id     INTEGER NOT NULL, -- ポートID

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
    status           VARCHAR(15) NOT NULL,                         -- 状態 (docked, rented, maintenance)
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 最終更新日時

-- < 主キー >
    PRIMARY KEY (bike_id),

-- < 制約 >
    CONSTRAINT bike_status_check CHECK (status IN ('docked', 'rented', 'maintenance'))
);



-- =====================================================
-- 自転車管理 (シェアサイクル事業者 x 自転車情報)
-- =====================================================
CREATE TABLE bike_management (
    bike_id     INTEGER NOT NULL, -- 自転車ID (主キー)
    operator_id INTEGER NOT NULL, -- 事業者ID

-- < 主キー & 外部キー >
    PRIMARY KEY (bike_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id),
    FOREIGN KEY (bike_id) REFERENCES share_bike(bike_id),
    UNIQUE (bike_id, operator_id) -- 事業者を跨いだ自転車IDの重複を防止    
);



-- =====================================================
-- 駐輪 (自転車情報 x ポート情報)
-- =====================================================
CREATE TABLE bike_parking (
    bike_id          INTEGER NOT NULL,                             -- 自転車ID (主キー)
    operator_id      INTEGER NOT NULL,                             -- 事業者ID
    current_port_id  INTEGER NULL,                                 -- 現在のポートID (貸出中はNULL)
    parked_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 駐輪日時

-- < 主キー & 外部キー >
    PRIMARY KEY (bike_id),
    FOREIGN KEY (operator_id, current_port_id) REFERENCES port_operation(operator_id, port_id),
    FOREIGN KEY (bike_id) REFERENCES share_bike(bike_id)
);



-- =====================================================
-- 移動ログ
-- =====================================================
CREATE TABLE move_record (
    log_id        BIGSERIAL,                            -- ログID (主キー)
    moved_bikes   INTEGER NOT NULL,                     -- 移動台数
    moved_at      TIMESTAMP NOT NULL DEFAULT NOW(),     -- 移動日時
    source        VARCHAR(10) NOT NULL DEFAULT 'admin', -- 種別 (admin/user)

-- < 主キー  >
    PRIMARY KEY (log_id),

-- < 制約 >
    CONSTRAINT moved_bikes_positive CHECK (moved_bikes > 0)
);



-- =====================================================
-- 移動自転車の所属
-- =====================================================
CREATE TABLE move_operator (
    log_id      BIGINT NOT NULL,
    operator_id INTEGER NOT NULL,
    
-- < 主キー & 外部キー >
    PRIMARY KEY (log_id),
    FOREIGN KEY (log_id) REFERENCES move_record(log_id),
    FOREIGN KEY (operator_id) REFERENCES share_bike_operator(operator_id)
);



-- =====================================================
-- 移動自転車の出発ポート
-- =====================================================
CREATE TABLE move_from (
    log_id       BIGINT NOT NULL,  -- ログID
    from_port_id INTEGER NOT NULL, -- 出発地ポートID
    
-- < 主キー & 外部キー >
    PRIMARY KEY (log_id),
    FOREIGN KEY (log_id) REFERENCES move_record(log_id),
    FOREIGN KEY (from_port_id) REFERENCES port_information(port_id)
);



-- =====================================================
-- 移動自転車の返却ポート
-- =====================================================
CREATE TABLE move_to (
    log_id     BIGINT NOT NULL,  -- ログID
    to_port_id INTEGER NOT NULL, -- 到着地ポートID
    
-- < 主キー & 外部キー >
    PRIMARY KEY (log_id),
    FOREIGN KEY (log_id) REFERENCES move_record(log_id),
    FOREIGN KEY (to_port_id) REFERENCES port_information(port_id)
);



-- =====================================================
-- 予約情報
-- =====================================================
CREATE TABLE reservation_info (
    reservation_id BIGSERIAL PRIMARY KEY,                          -- 予約ID
    status         VARCHAR(15) NOT NULL DEFAULT 'reserved',        -- 状態 (reserved, in_use, returned)
    reserved_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 予約日時
    started_at     TIMESTAMP NULL,                                 -- 利用開始日時
    returned_at    TIMESTAMP NULL,                                 -- 返却日時

-- < 制約 >
    CONSTRAINT reservation_status_check CHECK (status IN ('reserved', 'in_use', 'returned')),
    CONSTRAINT reservation_time_check CHECK (
        (status = 'reserved' AND started_at IS NULL AND returned_at IS NULL) OR
        (status = 'in_use' AND started_at IS NOT NULL AND returned_at IS NULL) OR
        (status = 'returned' AND started_at IS NOT NULL AND returned_at IS NOT NULL)
    )
);



-- =====================================================
-- 予約自転車
-- =====================================================
CREATE TABLE reservation_bike (
    reservation_id BIGINT NOT NULL,  -- 予約ID
    bike_id        INTEGER NOT NULL, -- 自転車ID

-- < 主キー & 外部キー >
    PRIMARY KEY (reservation_id),
    FOREIGN KEY (reservation_id) REFERENCES reservation_info(reservation_id),
    FOREIGN KEY (bike_id) REFERENCES share_bike(bike_id)
);



-- =====================================================
-- 予約自転車の出発ポート
-- =====================================================
CREATE TABLE reservation_start_port (
    reservation_id BIGINT NOT NULL,   -- 予約ID
    operator_id    INTEGER NOT NULL,  -- 事業者ID
    start_port_id  INTEGER NOT NULL,  -- 出発ポートID

-- < 主キー & 外部キー >
    PRIMARY KEY (reservation_id),
    FOREIGN KEY (reservation_id) REFERENCES reservation_info(reservation_id),
    FOREIGN KEY (operator_id, start_port_id) REFERENCES port_operation(operator_id, port_id)
);



-- =====================================================
-- 予約自転車の返却ポート
-- =====================================================
CREATE TABLE reservation_end_port (
    reservation_id BIGINT NOT NULL,  -- 予約ID
    operator_id    INTEGER NOT NULL, -- 事業者ID
    end_port_id    INTEGER NOT NULL, -- 返却ポートID

-- < 主キー & 外部キー >
    PRIMARY KEY (reservation_id),
    FOREIGN KEY (reservation_id) REFERENCES reservation_info(reservation_id),
    FOREIGN KEY (operator_id, end_port_id) REFERENCES port_operation(operator_id, port_id)
);



-- =====================================================
-- ポート状況ビュー (ポート情報 × ポート運営 × シェアサイクル事業者 × 駐輪情報)
-- =====================================================
CREATE VIEW v_port_status AS
SELECT
    p.port_id,
    po.operator_id,
    o.operator_name,
    o.operator_contact,
    p.port_name,
    p.port_latitude,
    p.port_longitude,
    p.capacity,
    COUNT(bp.bike_id) FILTER (WHERE sb.status='docked') AS bikes,
    p.capacity - COUNT(bp.bike_id) FILTER (WHERE sb.status='docked') AS free_docks
FROM port_information p
JOIN port_operation po ON po.port_id = p.port_id
JOIN share_bike_operator o ON o.operator_id = po.operator_id
LEFT JOIN bike_parking bp ON bp.current_port_id = p.port_id AND bp.operator_id = po.operator_id
LEFT JOIN share_bike sb ON sb.bike_id = bp.bike_id
GROUP BY p.port_id, po.operator_id, o.operator_name, o.operator_contact, p.port_name, p.port_latitude, p.port_longitude, p.capacity;



-- =====================================================
-- 自転車の現在状態ビュー（bike + management + parking）
-- =====================================================
CREATE VIEW v_bike_status AS
SELECT
    sb.bike_id,
    sb.status,
    sb.updated_at,
    bm.operator_id,
    o.operator_name,
    bp.current_port_id,
    p.port_name,
    bp.parked_at
FROM share_bike sb
LEFT JOIN bike_management bm ON bm.bike_id = sb.bike_id
LEFT JOIN share_bike_operator o ON o.operator_id = bm.operator_id
LEFT JOIN bike_parking bp ON bp.bike_id = sb.bike_id
LEFT JOIN port_information p ON p.port_id = bp.current_port_id;



-- =====================================================
-- 移動ログビュー（move_record + operator + from + to）
-- =====================================================
CREATE VIEW v_bike_move AS
SELECT
    mr.log_id,
    mr.moved_bikes,
    mr.moved_at,
    mr.source,
    mo.operator_id,
    op.operator_name,
    mf.from_port_id,
    pf.port_name AS from_port_name,
    mt.to_port_id,
    pt.port_name AS to_port_name
FROM move_record mr
LEFT JOIN move_operator mo ON mo.log_id = mr.log_id
LEFT JOIN share_bike_operator op ON op.operator_id = mo.operator_id
LEFT JOIN move_from mf ON mf.log_id = mr.log_id
LEFT JOIN port_information pf ON pf.port_id = mf.from_port_id
LEFT JOIN move_to mt ON mt.log_id = mr.log_id
LEFT JOIN port_information pt ON pt.port_id = mt.to_port_id;



-- =====================================================
-- 予約ビュー（reservation_info + bike + start/end）
-- =====================================================
CREATE VIEW v_bike_reservation AS
SELECT
    ri.reservation_id,
    ri.status,
    ri.reserved_at,
    ri.started_at,
    ri.returned_at,
    rb.bike_id,
    o.operator_name,
    rsp.start_port_id,
    ps.port_name AS start_port_name,
    rep.end_port_id,
    pe.port_name AS end_port_name
FROM reservation_info ri
LEFT JOIN reservation_bike rb ON rb.reservation_id = ri.reservation_id
LEFT JOIN bike_management bm ON bm.bike_id = rb.bike_id
LEFT JOIN share_bike_operator o ON o.operator_id = bm.operator_id
LEFT JOIN reservation_start_port rsp ON rsp.reservation_id = ri.reservation_id
LEFT JOIN port_information ps ON ps.port_id = rsp.start_port_id
LEFT JOIN reservation_end_port rep ON rep.reservation_id = ri.reservation_id
LEFT JOIN port_information pe ON pe.port_id = rep.end_port_id;



-- =====================================================
-- 索引
-- =====================================================
-- ポートの緯度経度検索
CREATE INDEX port_latlon_idx ON port_information(port_latitude, port_longitude);
-- ポート運営の事業者検索
CREATE INDEX IF NOT EXISTS port_operation_operator_idx ON port_operation(operator_id);
-- 自転車情報の状態検索
CREATE INDEX share_bike_status_idx ON share_bike(status);
-- 自転車管理の事業者検索
CREATE INDEX bike_management_operator_idx ON bike_management(operator_id);
-- 駐輪情報のポート・事業者検索
CREATE INDEX bike_parking_port_idx ON bike_parking(current_port_id);
CREATE INDEX bike_parking_operator_idx ON bike_parking(operator_id);
-- 移動記録の日時検索
CREATE INDEX move_record_moved_at_idx ON move_record(moved_at DESC);
-- 移動事業者の事業者検索
CREATE INDEX move_operator_operator_idx ON move_operator(operator_id);
-- 移動元の出発地検索
CREATE INDEX move_from_port_idx ON move_from(from_port_id);
-- 移動先の到着地検索
CREATE INDEX move_to_port_idx ON move_to(to_port_id);
-- 予約情報の状態検索
CREATE INDEX reservation_info_status_idx ON reservation_info(status);
CREATE INDEX reservation_info_reserved_at_idx ON reservation_info(reserved_at DESC);
-- 予約の自転車検索
CREATE INDEX reservation_bike_bike_idx ON reservation_bike(bike_id);