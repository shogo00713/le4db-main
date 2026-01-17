
--- ここからが課題5用
SELECT trip_name, destination_direction
FROM trip_information
WHERE trip_datetime = '平日';

SELECT
  route_name,
  trip_name,
  operator_name,
  destination_direction,
  trip_datetime
FROM
  route_information AS r
  NATURAL JOIN route_trip
  NATURAL JOIN trip_information AS t
  NATURAL JOIN route_operation
  NATURAL JOIN public_transport_operator AS o
WHERE
  o.operator_name = '京都市バス';

SELECT
  operator_name AS name,
  '公共交通'    AS category
FROM
  public_transport_operator

UNION

SELECT
  operator_name AS name,
  'シェアサイクル' AS category
FROM
  share_bike_operator;

SELECT
  stop_name
FROM
  stop_information
WHERE
  stop_type = 'バス停'

EXCEPT

SELECT
  s.stop_name
FROM
  stop_information AS s
  JOIN proximity AS p
    ON s.stop_id = p.stop_id
WHERE
  s.stop_type = 'バス停';

SELECT DISTINCT
  r.route_type
FROM
  route_information AS r
  JOIN route_trip AS rt
    ON r.route_id = rt.route_id
  JOIN trip_information AS t
    ON rt.trip_id = t.trip_id
WHERE
  t.trip_datetime = '平日';

SELECT
  s.stop_name,
  COUNT(*)        AS num_nearby_ports,
  MIN(p.distance) AS min_distance,
  MAX(p.distance) AS max_distance,
  AVG(p.distance) AS avg_distance
FROM
  stop_information AS s
  JOIN proximity AS p
    ON s.stop_id = p.stop_id
GROUP BY
  s.stop_id, s.stop_name;

SELECT
  r.route_name,
  r.route_type
FROM
  route_information AS r
WHERE
  -- 平日に運行する便が存在するか
  EXISTS (
    SELECT 1
    FROM route_trip AS rt
      JOIN trip_information AS t
        ON rt.trip_id = t.trip_id
    WHERE
      rt.route_id = r.route_id
      AND t.trip_datetime = '平日'
  )
  AND
  -- 全日に運行する便が存在するか
  EXISTS (
    SELECT 1
    FROM route_trip AS rt2
      JOIN trip_information AS t2
        ON rt2.trip_id = t2.trip_id
    WHERE
      rt2.route_id = r.route_id
      AND t2.trip_datetime = '全日'
  );

UPDATE route_information
SET route_color = CASE
  WHEN route_color = 'orenge' THEN 'orange'
  WHEN route_color = 'gyay'   THEN 'gray'
  ELSE route_color
END
WHERE route_color IN ('orenge', 'gyay');

SELECT route_id, route_name, route_color
FROM route_information
WHERE route_id IN (102, 201, 202, 203, 204, 501);

SELECT
  s.stop_name,
  p.port_name,
  pr.distance,
  pr.required_time
FROM
  proximity AS pr
  JOIN stop_information AS s
    ON pr.stop_id = s.stop_id
  JOIN port_information AS p
    ON pr.port_id = p.port_id
ORDER BY
  pr.distance ASC,
  pr.required_time ASC;

CREATE VIEW weekday_bus_trips AS
SELECT
  t.trip_id,
  t.trip_name,
  o.operator_name,
  r.route_name,
  t.destination_direction,
  t.trip_datetime
FROM
  trip_information AS t
  JOIN route_trip AS rt
    ON t.trip_id = rt.trip_id
  JOIN route_information AS r
    ON rt.route_id = r.route_id
  JOIN route_operation AS ro
    ON r.route_id = ro.route_id
  JOIN public_transport_operator AS o
    ON ro.operator_id = o.operator_id
WHERE
  r.route_type = 'バス'
  AND t.trip_datetime = '平日';
-- ここまでが課題5用



SELECT * FROM stop_information;

UPDATE stop_information SET stop_latitude=34.986693, stop_longitude=135.758359 WHERE stop_id=1;
UPDATE stop_information SET stop_latitude=35.003780, stop_longitude=135.769320 WHERE stop_id=2;
UPDATE stop_information SET stop_latitude=35.003078, stop_longitude=135.758199 WHERE stop_id=3;
UPDATE stop_information SET stop_latitude=35.010899, stop_longitude=135.768870 WHERE stop_id=4;
UPDATE stop_information SET stop_latitude=35.044565, stop_longitude=135.758341 WHERE stop_id=5;
UPDATE stop_information SET stop_latitude=35.028686, stop_longitude=135.773955 WHERE stop_id=6;
UPDATE stop_information SET stop_latitude=35.028185, stop_longitude=135.790540 WHERE stop_id=7;
UPDATE stop_information SET stop_latitude=35.038635, stop_longitude=135.733327 WHERE stop_id=8;
UPDATE stop_information SET stop_latitude=35.011865, stop_longitude=135.749717 WHERE stop_id=9;
UPDATE stop_information SET stop_latitude=35.014391, stop_longitude=135.677463 WHERE stop_id=10;
UPDATE stop_information SET stop_latitude=35.009976, stop_longitude=135.778120 WHERE stop_id=11;
UPDATE stop_information SET stop_latitude=34.977171, stop_longitude=135.774575 WHERE stop_id=12;
UPDATE stop_information SET stop_latitude=34.979337, stop_longitude=135.756052 WHERE stop_id=13;
UPDATE stop_information SET stop_latitude=35.029055, stop_longitude=135.759323 WHERE stop_id=14;
UPDATE stop_information SET stop_latitude=35.029666, stop_longitude=135.742392 WHERE stop_id=15;
UPDATE stop_information SET stop_latitude=35.003652, stop_longitude=135.732544 WHERE stop_id=16;
UPDATE stop_information SET stop_latitude=34.996124, stop_longitude=135.731581 WHERE stop_id=17;
UPDATE stop_information SET stop_latitude=35.011521, stop_longitude=135.742470 WHERE stop_id=18;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760063 WHERE stop_id=19;
UPDATE stop_information SET stop_latitude=34.997117, stop_longitude=135.778989 WHERE stop_id=20;
UPDATE stop_information SET stop_latitude=35.004690, stop_longitude=135.778403 WHERE stop_id=21;
UPDATE stop_information SET stop_latitude=34.989241, stop_longitude=135.769063 WHERE stop_id=22;
UPDATE stop_information SET stop_latitude=35.018906, stop_longitude=135.739025 WHERE stop_id=23;
UPDATE stop_information SET stop_latitude=35.026640, stop_longitude=135.731588 WHERE stop_id=24;
UPDATE stop_information SET stop_latitude=35.049047, stop_longitude=135.791947 WHERE stop_id=25;
UPDATE stop_information SET stop_latitude=35.062913, stop_longitude=135.785171 WHERE stop_id=101;
UPDATE stop_information SET stop_latitude=35.051313, stop_longitude=135.767243 WHERE stop_id=102;
UPDATE stop_information SET stop_latitude=35.044573, stop_longitude=135.758709 WHERE stop_id=103;
UPDATE stop_information SET stop_latitude=35.014594, stop_longitude=135.751670 WHERE stop_id=104;
UPDATE stop_information SET stop_latitude=35.010824, stop_longitude=135.759646 WHERE stop_id=105;
UPDATE stop_information SET stop_latitude=35.003712, stop_longitude=135.768760 WHERE stop_id=106;
UPDATE stop_information SET stop_latitude=34.989896, stop_longitude=135.772861 WHERE stop_id=107;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760122 WHERE stop_id=108;
UPDATE stop_information SET stop_latitude=35.011028, stop_longitude=135.741730 WHERE stop_id=151;
UPDATE stop_information SET stop_latitude=35.010824, stop_longitude=135.759646 WHERE stop_id=152;
UPDATE stop_information SET stop_latitude=35.010899, stop_longitude=135.768870 WHERE stop_id=153;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760063 WHERE stop_id=154;
UPDATE stop_information SET stop_latitude=34.991869, stop_longitude=135.816811 WHERE stop_id=155;
UPDATE stop_information SET stop_latitude=34.991869, stop_longitude=135.816811 WHERE stop_id=201;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760122 WHERE stop_id=202;
UPDATE stop_information SET stop_latitude=34.982042, stop_longitude=135.733375 WHERE stop_id=203;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760122 WHERE stop_id=204;
UPDATE stop_information SET stop_latitude=34.954856, stop_longitude=135.709826 WHERE stop_id=205;
UPDATE stop_information SET stop_latitude=34.986191, stop_longitude=135.760122 WHERE stop_id=251;
UPDATE stop_information SET stop_latitude=34.995702, stop_longitude=135.742394 WHERE stop_id=252;
UPDATE stop_information SET stop_latitude=35.011028, stop_longitude=135.741730 WHERE stop_id=253;
UPDATE stop_information SET stop_latitude=35.016997, stop_longitude=135.701088 WHERE stop_id=254;
UPDATE stop_information SET stop_latitude=35.018779, stop_longitude=135.681413 WHERE stop_id=255;
UPDATE stop_information SET stop_latitude=34.990091, stop_longitude=135.767628 WHERE stop_id=301;
UPDATE stop_information SET stop_latitude=34.996510, stop_longitude=135.768679 WHERE stop_id=302;
UPDATE stop_information SET stop_latitude=35.003233, stop_longitude=135.771931 WHERE stop_id=303;
UPDATE stop_information SET stop_latitude=35.009017, stop_longitude=135.772334 WHERE stop_id=304;
UPDATE stop_information SET stop_latitude=35.018668, stop_longitude=135.772244 WHERE stop_id=305;
UPDATE stop_information SET stop_latitude=35.030670, stop_longitude=135.773189 WHERE stop_id=306;













CREATE TABLE products (
  pid INTEGER PRIMARY KEY,
  name VARCHAR(20),
  price INTEGER
);

INSERT INTO products VALUES
(5, 'EEE', 90);