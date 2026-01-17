package dao;

import model.Stop;
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
}