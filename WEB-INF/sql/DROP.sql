-- 公共交通機関関連
DROP TABLE IF EXISTS 
    public_transport_operator,
    route_information,
    trip_information,
    stop_information,
    route_operation,
    route_trip,
    stop_at
CASCADE;

-- シェアサイクル関連
DROP TABLE IF EXISTS 
    share_bike_operator,
    port_information,
    port_operation,
    share_bike,
    bike_management,
    bike_parking,
    move_record,
    move_to,
    move_from,
    move_operator,
    reservation_info,
    reservation_bike,
    reservation_start_port,
    reservation_end_port
CASCADE;

-- 管理者関連
DROP TABLE IF EXISTS 
    accounts,
    bike_operator_accounts
CASCADE;
