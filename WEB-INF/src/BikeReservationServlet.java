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

    // データベース接続 & 初期化 (DatabaseConfigが大体やってくれる)
    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }

    // doPOST : 自転車予約関連
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

            // 簡易JSON パース
            String jsonStr = sb.toString();
            String action = extractJsonValue(jsonStr, "action");
            String operatorIdStr = extractJsonValue(jsonStr, "operator_id");
            String reservationIdStr = extractJsonValue(jsonStr, "reservation_id");
            String returnPortIdStr = extractJsonValue(jsonStr, "return_port_id");
            String startPortIdStr = extractJsonValue(jsonStr, "start_port_id");

            // アクションに応じて処理を分岐
            if ("reserve".equals(action)) {
                if (startPortIdStr == null || startPortIdStr.isEmpty()) {
                    sendJsonResponse(out, false, "start_port_id required", null);
                    return;
                }
                int startPortId;
                try {
                    startPortId = Integer.parseInt(startPortIdStr);
                } catch (NumberFormatException e) {
                    sendJsonResponse(out, false, "start_port_id must be numeric", null);
                    return;
                }
                // 予約する
                handleReserve(Integer.parseInt(operatorIdStr), startPortId, out);

            }
            else if ("start".equals(action)) {
                // 利用開始する
                handleStart(Long.parseLong(reservationIdStr), out);
            }
            else if ("return".equals(action)) {
                if (returnPortIdStr == null || returnPortIdStr.isEmpty()) {
                    sendJsonResponse(out, false, "return_port_id required", null);
                    return;
                }
                int returnPortId;
                try {
                    returnPortId = Integer.parseInt(returnPortIdStr);
                } catch (NumberFormatException e) {
                    sendJsonResponse(out, false, "return_port_id must be numeric", null);
                    return;
                }
                Long resId = Long.parseLong(reservationIdStr);
                // 返却する
                handleReturn(resId, returnPortId, out);
            }
            else if ("cancel".equals(action)) {
                // キャンセルする
                handleCancel(Long.parseLong(reservationIdStr), out);
            }
            else {
                // 例外
                sendJsonResponse(out, false, "Unknown action", null);
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(out, false, e.getMessage(), null);
        }
    }

    // メソッドたち

    // 予約処理
    private void handleReserve(int operatorId, int startPortId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 利用可能な自転車を取得
            String selectBikeSql = "SELECT bike_id FROM v_bike_status "
                    + "WHERE operator_id = ? AND status = 'docked' AND current_port_id = ? LIMIT 1";
            int bikeId = -1;
            try (PreparedStatement ps = conn.prepareStatement(selectBikeSql)) {
                ps.setInt(1, operatorId);
                ps.setInt(2, startPortId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bikeId = rs.getInt("bike_id");
                    }
                }
            }

            // 自転車が見つからない場合はエラー
            if (bikeId == -1) {
                sendJsonResponse(out, false, "自転車が見つかりませんでした", null);
                return;
            }

            // reservation_infoに記録
            long reservationId = -1;
            String insertReservationSql = "INSERT INTO reservation_info(status, reserved_at) "
                                        + "VALUES('reserved', CURRENT_TIMESTAMP) RETURNING reservation_id";
            try (PreparedStatement ps = conn.prepareStatement(insertReservationSql)) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reservationId = rs.getLong("reservation_id");
                    }
                }
            }

            if (reservationId == -1) {
                sendJsonResponse(out, false, "予約を作成できませんでした", null);
                return;
            }

            // reservation_bikeに記録
            String insertResBikeSql = "INSERT INTO reservation_bike(reservation_id, bike_id) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertResBikeSql)) {
                ps.setLong(1, reservationId);
                ps.setInt(2, bikeId);
                ps.executeUpdate();
            }

            // reservation_start_portに記録
            String insertStartPortSql = "INSERT INTO reservation_start_port(reservation_id, start_port_id) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertStartPortSql)) {
                ps.setLong(1, reservationId);
                ps.setInt(2, startPortId);
                ps.executeUpdate();
            }

            sendJsonResponse(out, true, "予約が成功しました", String.valueOf(reservationId));

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
    // 利用開始処理
    private void handleStart(long reservationId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

                // 予約情報を取得（30分以内の予約のみ有効）
                String selectSql = "SELECT bike_id, operator_id, start_port_id FROM v_bike_reservation "
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
                sendJsonResponse(out, false, "予約が期限切れか無効な状態です", null);
                return;
            }

            // start_port が記録されていない場合はエラー
            if (startPortId == null) {
                sendJsonResponse(out, false, "出発ポートが記録されていません", null);
                return;
            }

            // 予約状態を in_use に更新
            String updateSql = "UPDATE reservation_info "
                    + "SET status = 'in_use', started_at = CURRENT_TIMESTAMP "
                    + "WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, reservationId);
                ps.executeUpdate();
            }

            // 自転車の状態を rented に変更
            String updateBikeSql = "UPDATE share_bike SET status = 'rented', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE bike_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateBikeSql)) {
                ps.setInt(1, bikeId);
                ps.executeUpdate();
            }

            // bike_parkingのcurrent_port_idをNULLに（レンタル中はポートなし）
            String updateParkingSql = "UPDATE bike_parking SET current_port_id = NULL WHERE bike_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateParkingSql)) {
                ps.setInt(1, bikeId);
                ps.executeUpdate();
            }

            sendJsonResponse(out, true, "利用を開始しました", String.valueOf(reservationId));

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
    // 返却処理
    private void handleReturn(long reservationId, int returnPortId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 予約情報を取得
            String selectSql = "SELECT bike_id, operator_id, start_port_id FROM v_bike_reservation "
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
                sendJsonResponse(out, false, "予約が見つかりません", null);
                return;
            }

            // 返却ポートはクライアント指定必須
            if (returnPortId <= 0) {
                sendJsonResponse(out, false, "返却ポートが指定されていません", null);
                return;
            }

            // 予約状態を returned に更新
            String updateSql = "UPDATE reservation_info "
                    + "SET status = 'returned', returned_at = CURRENT_TIMESTAMP "
                    + "WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, reservationId);
                ps.executeUpdate();
            }

            // end_port_idを記録
            String insertEndPortSql = "INSERT INTO reservation_end_port(reservation_id, end_port_id) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertEndPortSql)) {
                ps.setLong(1, reservationId);
                ps.setInt(2, returnPortId);
                ps.executeUpdate();
            }

            // 自転車の状態を docked に変更
            String updateBikeSql = "UPDATE share_bike SET status = 'docked', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE bike_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateBikeSql)) {
                ps.setInt(1, bikeId);
                ps.executeUpdate();
            }

            // bike_parkingを更新してポート情報を記録
            String updateParkingSql = "UPDATE bike_parking SET current_port_id = ?, parked_at = CURRENT_TIMESTAMP WHERE bike_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateParkingSql)) {
                ps.setInt(1, returnPortId);
                ps.setInt(2, bikeId);
                ps.executeUpdate();
            }

            // ユーザー利用による自転車の移動を配車ログに記録
            int fromPortId = (startPortId == null ? -1 : startPortId.intValue());
            int toPortId = returnPortId;
            if (fromPortId > 0 && toPortId > 0 && fromPortId != toPortId) {
                // move_recordに記録
                String insertRecordSql = "INSERT INTO move_record(moved_bikes, source) VALUES(1, 'user') RETURNING log_id";
                long logId = -1;
                try (PreparedStatement ps = conn.prepareStatement(insertRecordSql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) logId = rs.getLong("log_id");
                    }
                }

                if (logId > 0) {
                    // move_fromに記録
                    String insertFromSql = "INSERT INTO move_from(log_id, from_port_id) VALUES(?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertFromSql)) {
                        ps.setLong(1, logId);
                        ps.setInt(2, fromPortId);
                        ps.executeUpdate();
                    }

                    // move_toに記録
                    String insertToSql = "INSERT INTO move_to(log_id, to_port_id) VALUES(?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertToSql)) {
                        ps.setLong(1, logId);
                        ps.setInt(2, toPortId);
                        ps.executeUpdate();
                    }
                }
            }

            sendJsonResponse(out, true, "自転車を返却しました", String.valueOf(reservationId));

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
    // キャンセル処理
    private void handleCancel(long reservationId, PrintWriter out) throws SQLException {
        Connection conn = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 予約情報を取得
            String selectSql = "SELECT bike_id, operator_id FROM v_bike_reservation "
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
                sendJsonResponse(out, false, "予約が見つからないかキャンセルできませんでした", null);
                return;
            }

            // 予約を削除（reservation_info削除でCASCADEにより関連テーブルも削除）
            String deleteSql = "DELETE FROM reservation_info WHERE reservation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setLong(1, reservationId);
                ps.executeUpdate();
            }

            sendJsonResponse(out, true, "予約をキャンセルしました", null);

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
    // 簡易JSON 解析メソッド
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
    // JSON レスポンス送信メソッド
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
    // JSON エスケープメソッド
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
