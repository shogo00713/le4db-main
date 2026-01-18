package dao;


import model.NearByPorts;
import java.sql.*;
import java.util.*;
import static util.GeoUtils.distanceMeters;
import static util.HtmlUtils.hasColumn;

public class PortQueries {


    /**
     * 最寄りのポートを検索
     * @param conn
     * @param centerLat
     * @param centerLon
     * @param radiusM
     * @param limit
     * @param needBikes
     * @param needFreeDocks
     * @return
     * @throws SQLException
     */
    public static List<NearByPorts> getNearByPorts(Connection conn, double centerLat, double centerLon,
            int radiusM, int limit, boolean needBikes, boolean needFreeDocks) throws SQLException {

        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerLat)));

        String sql = "SELECT * FROM v_port_status " +
            "WHERE port_latitude BETWEEN ? AND ? " +
            "  AND port_longitude BETWEEN ? AND ? ";

        if (needBikes)
            sql += " AND bikes > 0 ";
        if (needFreeDocks)
            sql += " AND free_docks > 0 ";

        List<NearByPorts> tmp = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerLat - dLat);
            ps.setDouble(idx++, centerLat + dLat);
            ps.setDouble(idx++, centerLon - dLon);
            ps.setDouble(idx++, centerLon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                boolean hasOperatorContact = hasColumn(meta, "operator_contact");

                while (rs.next()) {
                    int pid = rs.getInt("port_id");
                    int opid = rs.getInt("operator_id");
                    String opn = rs.getString("operator_name");
                    String pn = rs.getString("port_name");
                    double lat = rs.getDouble("port_latitude");
                    double lon = rs.getDouble("port_longitude");
                    String contact = hasOperatorContact ? rs.getString("operator_contact") : null;

                    int dist = (int) Math.round(distanceMeters(centerLat, centerLon, lat, lon));
                    if (dist <= radiusM) {
                        tmp.add(new NearByPorts(pid, opid, opn, contact, pn, lat, lon, dist));
                    }
                }
            }
        }

        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));
        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }
}