package dao;

import model.TransitPath;

import static util.TimeUtils.hhmm;

import java.sql.*;
import java.util.*;

/**
 * 交通機関（電車・バス）の経路に関するデータベースクエリ
 */
public class TransitQueries {
    
    /**
     * 直通経路を検索（出発地から目的地へ乗り換えなし）
     * 
     * @param conn データベース接続
     * @param fromStopId 出発停留所 ID
     * @param toStopId 目的停留所 ID
     * @param departureTime 出発時刻（"HH:MM" 形式）
     * @param operatingDay 運行日（"Monday" など）
     * @param limit 取得する結果の最大数
     * @return 見つかった経路のリスト
     */
    public static List<TransitPath> searchDirectTransit(Connection conn,
            int fromStopId, int toStopId, String departureTime, 
            String operatingDay, int limit) throws SQLException {
        String sql = ""
                + "SELECT "
                + "  t.trip_id AS trip_id, "
                + "  r.route_name AS route_name, "
                + "  r.route_color AS route_color, "
                + "  t.trip_name AS trip_name, "
                + "  sa_from.stop_id AS from_stop_id, "
                + "  sf.stop_name AS from_stop_name, "
                + "  sa_from.departure_time AS dep_time, "
                + "  sa_to.stop_id AS to_stop_id, "
                + "  st.stop_name AS to_stop_name, "
                + "  sa_to.arrival_time AS arr_time "
                + "FROM stop_at sa_from "
                + "JOIN stop_information sf ON sf.stop_id = sa_from.stop_id "
                + "JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id "
                + "JOIN stop_information st ON st.stop_id = sa_to.stop_id "
                + "JOIN trip_information t ON t.trip_id = sa_from.trip_id "
                + "JOIN route_trip rt ON rt.trip_id = t.trip_id "
                + "JOIN route_information r ON r.route_id = rt.route_id "
                + "WHERE sa_from.stop_id = ? "
                + "  AND sa_to.stop_id = ? "
                + "  AND sa_from.arrival_order < sa_to.arrival_order "
                + "  AND sa_from.departure_time >= ?::time ";

        if ("平日".equals(operatingDay)) {
            sql += " AND t.trip_datetime IN ('全日','平日') ";
        } else if ("休日".equals(operatingDay)) {
            sql += " AND t.trip_datetime IN ('全日','休日') ";
        }

        sql += " ORDER BY sa_to.arrival_time ASC, sa_from.departure_time ASC ";
        sql += " LIMIT ?";

        List<TransitPath> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setInt(idx++, toStopId);
            ps.setString(idx++, departureTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new TransitPath(
                            rs.getInt("trip_id"),
                            rs.getString("route_name"),
                            rs.getString("route_color"),
                            rs.getString("trip_name"),
                            rs.getInt("from_stop_id"),
                            rs.getString("from_stop_name"),
                            hhmm(rs.getString("dep_time")),
                            rs.getInt("to_stop_id"),
                            rs.getString("to_stop_name"),
                            hhmm(rs.getString("arr_time"))));
                }
            }
        }
        return results;
    }
}