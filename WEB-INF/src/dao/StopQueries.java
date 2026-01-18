package dao;

import model.NearByStops;
import model.Stop;

import static util.GeoUtils.distanceMeters;

import java.sql.*;
import java.util.*;

/**
 * 停留所に関するデータベースクエリ
 */
public class StopQueries {
    
    /**
     * 停留所 ID から停留所情報を取得
     * @param conn データベース接続
     * @param stopId 停留所 ID
     * @return 停留所情報、存在しない場合は null
     */
    public static Stop getStopById(Connection conn, int stopId) throws SQLException {
        String sql = "SELECT stop_id, stop_name, stop_type, stop_latitude, stop_longitude " +
                     "FROM stop_information " +
                     "WHERE stop_id = ?";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, stopId);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Stop(
                        rs.getInt("stop_id"),
                        rs.getString("stop_name"),
                        rs.getString("stop_type"),
                        rs.getDouble("stop_latitude"),
                        rs.getDouble("stop_longitude")
                    );
                }
            }
        }
        return null;
    }
    
    /**
     * 緯度経度から最寄りの停留所を検索
     * @param conn データベース接続
     * @param lat 緯度
     * @param lon 経度
     * @param radiusM 検索範囲（メートル）
     * @return 見つかった停留所のリスト（距離の近い順）
     */
    public static List<Stop> findNearbyStops(Connection conn, 
            double lat, double lon, int radiusM) throws SQLException {
        
    String sql = "SELECT stop_id, stop_name, stop_type, stop_latitude, stop_longitude, dist " +
                 "FROM ( " +
                 "  SELECT stop_id, stop_name, stop_type, stop_latitude, stop_longitude, " +
                 "         SQRT(POW(stop_latitude - ?, 2) + POW(stop_longitude - ?, 2)) * 111000 AS dist " +
                 "  FROM stop_information " +
                 ") sub " +
                 "WHERE dist <= ? " +
                 "ORDER BY dist ASC";
        
        List<Stop> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, lat);
            ps.setDouble(2, lon);
            ps.setInt(3, radiusM);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Stop(
                        rs.getInt("stop_id"),
                        rs.getString("stop_name"),
                        rs.getString("stop_type"),
                        rs.getDouble("stop_latitude"),
                        rs.getDouble("stop_longitude")
                    ));
                }
            }
        }
        return results;
    }
    
    /**
     * 停留所名から停留所を検索
     * @param conn データベース接続
     * @param stopName 停留所名
     * @return 見つかった停留所のリスト
     */
    public static List<Stop> findByName(Connection conn, String stopName, int limit) throws SQLException {
        String sql = "SELECT stop_id, stop_name, stop_type, stop_latitude, stop_longitude "
                   + "FROM stop_information "
                   + "WHERE stop_name ILIKE ? "
                   + "ORDER BY CASE "
                   + "WHEN stop_name = ? THEN 0 "
                   + "WHEN stop_name ILIKE ? THEN 1 "
                   + "ELSE 2 END, "
                   + "CHAR_LENGTH(stop_name) ASC "
                   + "LIMIT ? ";
        
        List<Stop> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, "%" + stopName + "%"); // 部分一致
            ps.setString(idx++, stopName); // 完全一致
            ps.setString(idx++, stopName + "%"); // 前方一致
            ps.setInt(idx++, limit);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Stop(
                        rs.getInt("stop_id"),
                        rs.getString("stop_name"),
                        rs.getString("stop_type"),
                        rs.getDouble("stop_latitude"),
                        rs.getDouble("stop_longitude")
                    ));
                }
            }
        }
        return results;
    }


    /**
     * 停留所IDから近くの停留所を検索
     * @param conn データベース接続
     * @param centerStopId 中心停留所ID
     * @param radiusM 検索範囲（メートル）
     * @param limit 取得する結果の最大数
     * @return 見つかった近くの停留所のリスト（距離の近い順）
     */
    public static List<NearByStops> getNearByStops(Connection conn, int centerStopId, int radiusM, int limit) throws SQLException {
        Stop centerstop = StopQueries.getStopById(conn, centerStopId);
        if (centerstop == null)
            return new ArrayList<>();

        // 半径radiusMを緯度経度の範囲に雑に変換（高速化）
        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerstop.lat)));

        String sql = "SELECT stop_id, stop_name, stop_latitude, stop_longitude "
                + "FROM stop_information "
                + "WHERE stop_latitude BETWEEN ? AND ? "
                + "  AND stop_longitude BETWEEN ? AND ?";

        List<NearByStops> tmp = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerstop.lat - dLat);
            ps.setDouble(idx++, centerstop.lat + dLat);
            ps.setDouble(idx++, centerstop.lon - dLon);
            ps.setDouble(idx++, centerstop.lon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt("stop_id");
                    String name = rs.getString("stop_name");
                    double lat = rs.getDouble("stop_latitude");
                    double lon = rs.getDouble("stop_longitude");

                    int meters = (int) Math.round(distanceMeters(centerstop.lat, centerstop.lon, lat, lon));
                    if (meters <= radiusM) {
                        tmp.add(new NearByStops(sid, name, meters, lat, lon));
                    }
                }
            }
        }

        // 近い順
        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));

        if (tmp.isEmpty() || tmp.get(0).stopId != centerStopId) {
            tmp.add(0, new NearByStops(centerStopId, centerstop.name, 0, centerstop.lat, centerstop.lon));
        }

        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }



    // 出発停留所から降りれる停留所を列挙
    public static List<Stop> listTransferCandidates(Connection conn, int fromStopId, String baseTime, String day, int limit) throws SQLException {

        String sql = ""
                + "SELECT DISTINCT "
                + "  t.trip_id AS trip_id, "
                + "  sa_to.stop_id AS mid_stop_id, "
                + "  st.stop_name AS mid_stop_name, "
                + "  st.stop_type AS mid_stop_type, "
                + "  st.stop_latitude AS mid_stop_lat, "
                + "  st.stop_longitude AS mid_stop_lon, "
                + "  sa_to.arrival_time AS arr_time "
                + "FROM stop_at sa_from "
                + "JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id "
                + "JOIN stop_information st ON st.stop_id = sa_to.stop_id "
                + "JOIN trip_information t ON t.trip_id = sa_from.trip_id "
                + "WHERE sa_from.stop_id = ? "
                + "  AND sa_from.departure_time >= ?::time "
                + "  AND sa_from.arrival_order < sa_to.arrival_order ";

        if ("平日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','平日') ";
        } else if ("休日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','休日') ";
        }

        sql += " ORDER BY sa_to.arrival_time ASC LIMIT ?";

        List<Stop> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setString(idx++, baseTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {

                    results.add(new Stop (
                            rs.getInt("mid_stop_id"),
                            rs.getString("mid_stop_name"),
                            rs.getString("mid_stop_type"),
                            rs.getDouble("mid_stop_lat"),
                            rs.getDouble("mid_stop_lon")));

                }
            }
        }
        return results;
    }



}