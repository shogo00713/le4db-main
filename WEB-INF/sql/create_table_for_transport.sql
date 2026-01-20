-- =====================================================
-- 公共交通事業者
-- =====================================================
CREATE TABLE public_transport_operator (
-- < 属性 >
    operator_id      SERIAL,               -- 公共交通事業者ID (主キー)
    operator_name    VARCHAR(30) NOT NULL, -- 公共交通事業者名
    operator_type    VARCHAR(10) NOT NULL, -- 事業種別
    operator_contact VARCHAR(11) NOT NULL, -- 公共交通事業者連絡先
-- < 主キー >
    PRIMARY KEY (operator_id)
);


-- =====================================================
-- 路線情報
-- =====================================================
CREATE TABLE route_information (
-- < 属性 >
    route_id    SERIAL,               -- 路線ID (主キー)
    route_name  VARCHAR(20) NOT NULL, -- 路線名
    route_type  VARCHAR(10) NOT NULL, -- 路線種類
    route_color VARCHAR(10),          -- 路線色 (任意)

-- < 主キー >
    PRIMARY KEY (route_id)
);


-- =====================================================
-- 便情報
-- =====================================================
CREATE TABLE trip_information (
-- < 属性 >
    trip_id               SERIAL,               -- 便ID (主キー)
    trip_name             VARCHAR(30) NOT NULL, -- 便名
    destination_direction VARCHAR(4) NOT NULL,  -- 行先方向
    trip_datetime         VARCHAR(4) NOT NULL,  -- 運行日時

-- < 主キー >
    PRIMARY KEY (trip_id)
);


-- =====================================================
-- 停留所情報
-- =====================================================
CREATE TABLE stop_information (
-- < 属性 >
    stop_id        SERIAL,                    -- 停留所ID (主キー)
    stop_name      VARCHAR(20) NOT NULL,      -- 停留所名
    stop_type      VARCHAR(10) NOT NULL,      -- 停留所種類
    stop_latitude  DOUBLE PRECISION NOT NULL, -- 緯度
    stop_longitude DOUBLE PRECISION NOT NULL, -- 経度

-- < 主キー >
    PRIMARY KEY (stop_id)
);



-- =====================================================
-- 路線運行 （公共交通事業者 × 路線情報)
-- =====================================================
CREATE TABLE route_operation (
-- < 属性 >
    route_id    INTEGER NOT NULL, -- 路線ID (主キー)
    operator_id INTEGER NOT NULL, -- 公共交通事業者ID

-- < 主キー & 外部キー >
    PRIMARY KEY (route_id),
    FOREIGN KEY (route_id)    REFERENCES route_information(route_id),
    FOREIGN KEY (operator_id) REFERENCES public_transport_operator(operator_id)
);


-- =====================================================
-- 路線の便（路線情報 × 便情報）
-- =====================================================
CREATE TABLE route_trip (
-- < 属性 >
    trip_id  INTEGER NOT NULL, -- 便ID (主キー)
    route_id INTEGER NOT NULL, -- 路線ID

-- < 主キー & 外部キー >
    PRIMARY KEY (trip_id),
    FOREIGN KEY (trip_id)  REFERENCES trip_information(trip_id),
    FOREIGN KEY (route_id) REFERENCES route_information(route_id)
);


-- =====================================================
-- 停車 (便情報 x 停留所情報)
-- =====================================================
CREATE TABLE stop_at (
-- < 属性 >
    trip_id        INTEGER NOT NULL, -- 便ID (主キー)
    stop_id        INTEGER NOT NULL, -- 停留所ID (主キー)
    arrival_order  INTEGER NOT NULL, -- 到着順
    arrival_time   TIME,             -- 到着時間 (HH:MM:SS)
    departure_time TIME,             -- 発車時間 (HH:MM:SS)

-- < 主キー & 外部キー >
    PRIMARY KEY (trip_id, stop_id),
    FOREIGN KEY (trip_id) REFERENCES trip_information(trip_id),
    FOREIGN KEY (stop_id) REFERENCES stop_information(stop_id),
    UNIQUE (trip_id, arrival_order) -- 同一便内での到着順は一意の一意制約
);


-- =====================================================
-- 索引
-- =====================================================
CREATE INDEX stop_at_stop_depart_idx ON stop_at (stop_id, departure_time, trip_id, arrival_order);
CREATE INDEX stop_at_stop_order_idx ON stop_at (stop_id, departure_time, arrival_order, trip_id);
CREATE INDEX stop_latlon_idx ON stop_information (stop_latitude, stop_longitude);
CREATE INDEX route_trip_route_idx ON route_trip (route_id, trip_id);
CREATE INDEX route_operation_operator_idx ON route_operation (operator_id, route_id);
