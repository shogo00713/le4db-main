

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BikeReservationServlet extends HttpServlet {

    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();

        try {
            // JSON リクエスト本体を読み込み
            StringBuilder sb = new StringBuilder();
            String line;
            java.io.BufferedReader reader = request.getReader();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            // 簡易JSON パース（本番環境では JSON ライブラリを使用）
            String jsonStr = sb.toString();
            String action = extractJsonValue(jsonStr, "action");
            String operatorIdStr = extractJsonValue(jsonStr, "operator_id");
            String reservationIdStr = extractJsonValue(jsonStr, "reservation_id");
            String returnPortIdStr = extractJsonValue(jsonStr, "return_port_id");
            String startPortIdStr = extractJsonValue(jsonStr, "start_port_id");

            if ("reserve".equals(action)) {
                Integer startPortId = null;
                if (startPortIdStr != null && !startPortIdStr.isEmpty()) {
                    try {
                        startPortId = Integer.parseInt(startPortIdStr);
                    } catch (NumberFormatException ignore) {}
                }
                handleReserve(Integer.parseInt(operatorIdStr), startPortId, out);
            } else if ("start".equals(action)) {
                handleStart(Long.parseLong(reservationIdStr), out);
            } else if ("return".equals(action)) {
                Long resId = Long.parseLong(reservationIdStr);
                Integer returnPortId = null;
                if (returnPortIdStr != null && !returnPortIdStr.isEmpty()) {
                    try {
                        returnPortId = Integer.parseInt(returnPortIdStr);
                    } catch (NumberFormatException ignore) {}
                }
                handleReturn(resId, returnPortId, out);
            } else if ("cancel".equals(action)) {
                handleCancel(Long.parseLong(reservationIdStr), out);
            } else {
                sendJsonResponse(out, false, "Unknown action", null);
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(out, false, e.getMessage(), null);
        }
    }

    private void handleReserve(int operatorId, Integer startPortId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 利用可能な自転車を取得 (docked 状態)
            // startPortIdが指定されている場合は、そのポートにある自転車のみを検索
            String selectBikeSql;
            if (startPortId != null && startPortId > 0) {
                selectBikeSql = "SELECT bike_id FROM share_bike "
                        + "WHERE operator_id = ? AND status = 'docked' AND current_port_id = ? LIMIT 1";
            } else {
                selectBikeSql = "SELECT bike_id FROM share_bike "
                        + "WHERE operator_id = ? AND status = 'docked' LIMIT 1";
            }
            int bikeId = -1;
            Integer actualStartPortId = null;
            try (PreparedStatement ps = conn.prepareStatement(selectBikeSql)) {
                ps.setInt(1, operatorId);
                if (startPortId != null && startPortId > 0) {
                    ps.setInt(2, startPortId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bikeId = rs.getInt("bike_id");
                    }
                }
            }

            if (bikeId == -1) {
                sendJsonResponse(out, false, "Available bikes not found", null);
                return;
            }

            // 自転車の現在のポートを取得
            String getCurrentPortSql = "SELECT current_port_id FROM share_bike WHERE bike_id = ? AND operator_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(getCurrentPortSql)) {
                ps.setInt(1, bikeId);
                ps.setInt(2, operatorId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int cp = rs.getInt("current_port_id");
                        if (!rs.wasNull()) actualStartPortId = cp;
                    }
                }
            }

            // 予約を記録（start_port_idも記録）
            String insertReservationSql = "INSERT INTO share_bike_reservation "
                    + "(bike_id, operator_id, status, reserved_at, start_port_id) "
                    + "VALUES (?, ?, 'reserved', CURRENT_TIMESTAMP, ?) "
                    + "RETURNING reservation_id";
            long reservationId = -1;
            try (PreparedStatement ps = conn.prepareStatement(insertReservationSql)) {
                ps.setInt(1, bikeId);
                ps.setInt(2, operatorId);
                if (actualStartPortId != null) {
                    ps.setInt(3, actualStartPortId);
                } else {
                    ps.setNull(3, java.sql.Types.INTEGER);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reservationId = rs.getLong("reservation_id");
                    }
                }
            }

            if (reservationId == -1) {
                sendJsonResponse(out, false, "Failed to create reservation", null);
                return;
            }

            sendJsonResponse(out, true, "Reservation successful", String.valueOf(reservationId));

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleStart(long reservationId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

                // 予約情報を取得（30分以内の予約のみ有効）
                String selectSql = "SELECT bike_id, operator_id, start_port_id FROM share_bike_reservation "
                    + "WHERE reservation_id = ? AND status = 'reserved' "
                    + "AND reserved_at > CURRENT_TIMESTAMP - INTERVAL '30 minutes'";
            int bikeId = -1;
            int operatorId = -1;
            Integer startPortId = null;
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, reservationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bikeId = rs.getInt("bike_id");
                        operatorId = rs.getInt("operator_id");
                        int sp = rs.getInt("start_port_id");
                        if (!rs.wasNull()) startPortId = sp;
                    }
                }
            }

            if (bikeId == -1) {
                sendJsonResponse(out, false, "Reservation expired or invalid state", null);
                return;
            }

            // 自転車の現在のポートを取得（貸出前のポート＝start_port）
            if (startPortId == null) {
                String getCurrentPortSql = "SELECT current_port_id FROM share_bike WHERE bike_id = ? AND operator_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(getCurrentPortSql)) {
                    ps.setInt(1, bikeId);
                    ps.setInt(2, operatorId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int cp = rs.getInt("current_port_id");
                            if (!rs.wasNull()) startPortId = cp;
                        }
                    }
                }
            }

            // 予約状態を in_use に更新
            String updateSql = "UPDATE share_bike_reservation "
                    + "SET status = 'in_use', started_at = CURRENT_TIMESTAMP, start_port_id = ? "
                    + "WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                if (startPortId == null) {
                    ps.setNull(1, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(1, startPortId);
                }
                ps.setLong(2, reservationId);
                ps.executeUpdate();
            }

            // 自転車の状態を rented に変更
            String updateBikeSql = "UPDATE share_bike SET status = 'rented', current_port_id = NULL, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE bike_id = ? AND operator_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateBikeSql)) {
                ps.setInt(1, bikeId);
                ps.setInt(2, operatorId);
                ps.executeUpdate();
            }

            sendJsonResponse(out, true, "Usage started", String.valueOf(reservationId));

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleReturn(long reservationId, Integer requestedReturnPortId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // JSON リクエストから return_port_id を取得（別途実装のため、ここでは requestの再読み込みは不要）
            // 呼び出し元の doPost で既に JSON 解析済み
            // 後で呼び出し元で追加処理

            // 予約情報を取得
            String selectSql = "SELECT bike_id, operator_id, start_port_id FROM share_bike_reservation "
                    + "WHERE reservation_id = ? AND status = 'in_use'";
            int bikeId = -1;
            int operatorId = -1;
            Integer startPortId = null;
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, reservationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bikeId = rs.getInt("bike_id");
                        operatorId = rs.getInt("operator_id");
                        int sp = rs.getInt("start_port_id");
                        if (!rs.wasNull()) startPortId = sp;
                    }
                }
            }

            if (bikeId == -1) {
                sendJsonResponse(out, false, "Reservation not found or not in use", null);
                return;
            }

            // 返却ポート決定：指定されたポートがあればそれを使う、なければデフォルト
            int returnPortId = -1;
            if (requestedReturnPortId != null && requestedReturnPortId > 0) {
                returnPortId = requestedReturnPortId;
            } else {
                // フォールバック：最初の docked ポートを返却ポートとして取得
                String getPortSql = "SELECT p.port_id FROM port_information p "
                        + "JOIN port_operation po ON po.port_id = p.port_id "
                        + "WHERE po.operator_id = ? "
                        + "ORDER BY p.port_id ASC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(getPortSql)) {
                    ps.setInt(1, operatorId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            returnPortId = rs.getInt("port_id");
                        }
                    }
                }
            }

            // 予約状態を returned に更新
            String updateSql = "UPDATE share_bike_reservation "
                    + "SET status = 'returned', returned_at = CURRENT_TIMESTAMP, end_port_id = ? "
                    + "WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, returnPortId == -1 ? 1 : returnPortId);
                ps.setLong(2, reservationId);
                ps.executeUpdate();
            }

            // 自転車の状態を docked に変更し、ポートを更新
            String updateBikeSql = "UPDATE share_bike SET status = 'docked', current_port_id = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE bike_id = ? AND operator_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateBikeSql)) {
                ps.setInt(1, returnPortId == -1 ? 1 : returnPortId);
                ps.setInt(2, bikeId);
                ps.setInt(3, operatorId);
                ps.executeUpdate();
            }

            // ユーザー利用による自転車の移動を配車ログに1件として記録（source='user'）
            int fromPortId = (startPortId == null ? -1 : startPortId.intValue());
            int toPortId = (returnPortId == -1 ? 1 : returnPortId);
            if (fromPortId > 0 && toPortId > 0 && fromPortId != toPortId) {
                String insertLogSql = "INSERT INTO bike_move_log (operator_id, from_port_id, to_port_id, moved_bikes, moved_at, source) "
                                    + "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertLogSql)) {
                    ps.setInt(1, operatorId);
                    ps.setInt(2, fromPortId);
                    ps.setInt(3, toPortId);
                    ps.setString(4, "user");
                    ps.executeUpdate();
                }
            }

            sendJsonResponse(out, true, "Bike returned", String.valueOf(reservationId));

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleCancel(long reservationId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 予約情報を取得
            String selectSql = "SELECT bike_id, operator_id FROM share_bike_reservation "
                    + "WHERE reservation_id = ? AND status = 'reserved'";
            int bikeId = -1;
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setLong(1, reservationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bikeId = rs.getInt("bike_id");
                    }
                }
            }

                if (bikeId == -1) {
                sendJsonResponse(out, false, "Reservation not found or cannot be cancelled", null);
                return;
            }

            // 予約を削除（または marked as cancelled）
            String deleteSql = "DELETE FROM share_bike_reservation WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setLong(1, reservationId);
                ps.executeUpdate();
            }

            sendJsonResponse(out, true, "Reservation cancelled", null);

        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int index = json.indexOf(searchKey);
        if (index == -1) return "";

        int startIdx = index + searchKey.length();
        char[] chars = json.toCharArray();

        // Skip whitespace
        while (startIdx < chars.length && Character.isWhitespace(chars[startIdx])) {
            startIdx++;
        }

        // Extract value
        StringBuilder value = new StringBuilder();
        if (startIdx < chars.length) {
            if (chars[startIdx] == '"') {
                startIdx++;
                while (startIdx < chars.length && chars[startIdx] != '"') {
                    if (chars[startIdx] == '\\') {
                        startIdx++;
                    }
                    value.append(chars[startIdx++]);
                }
            } else {
                // Number or null
                while (startIdx < chars.length && chars[startIdx] != ',' && chars[startIdx] != '}') {
                    value.append(chars[startIdx++]);
                }
            }
        }

        return value.toString().trim();
    }

    private void sendJsonResponse(PrintWriter out, boolean success, String message, String reservationId) {
        out.print("{");
        out.print("\"success\":" + (success ? "true" : "false") + ",");
        if (message != null) {
            out.print("\"error\":\"" + escapeJson(message) + "\"");
        }
        if (reservationId != null) {
            out.print(",\"reservation_id\":" + reservationId);
        }
        out.print("}");
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
