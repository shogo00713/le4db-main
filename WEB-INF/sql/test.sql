DROP INDEX IF EXISTS stop_at_stop_depart_idx;
DROP INDEX IF EXISTS stop_at_stop_order_idx;
DROP INDEX IF EXISTS stop_latlon_idx;
DROP INDEX IF EXISTS route_trip_route_idx;

EXPLAIN ANALYZE
SELECT DISTINCT ON (sa_to.stop_id)
    sa_to.stop_id AS mid_stop_id,
    st.stop_name AS mid_stop_name,
    st.stop_type AS mid_stop_type,
    st.stop_latitude AS mid_stop_lat,
    st.stop_longitude AS mid_stop_lon,
    sa_to.arrival_time AS arr_time
FROM stop_at sa_from
JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id
JOIN stop_information st ON st.stop_id = sa_to.stop_id
JOIN trip_information t ON t.trip_id = sa_from.trip_id
WHERE sa_from.stop_id = 111
  AND sa_from.departure_time >= '08:30'::time
  AND sa_from.arrival_order < sa_to.arrival_order 
  AND t.trip_datetime IN ('全日','平日')
ORDER BY sa_to.stop_id, sa_to.arrival_time ASC LIMIT 50;