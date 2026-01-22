import static util.HtmlUtils.esc;
import static util.HtmlUtils.safe;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;




public class PortAdminServlet extends HttpServlet {

    // データベース接続 & 初期化
    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }
    Connection conn = null; // 認証 & 接続用

    private static class PortRow {
        final int portId;
        final String operatorName;
        final String portName;
        final int bikes;
        final int freeDocks;

        PortRow(int portId, String operatorName, String portName, int bikes, int freeDocks) {
            this.portId = portId;
            this.operatorName = operatorName;
            this.portName = portName;
            this.bikes = bikes;
            this.freeDocks = freeDocks;
        }
    }

    private Integer readOperatorIdForUpdate(Connection conn, int portId) throws SQLException {
        String sql = "SELECT operator_id FROM port_operation WHERE port_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getInt("operator_id");
            }
        }
    }

    private int countBikesAtPort(Connection conn, int portId) throws SQLException {
        String sql = "SELECT COUNT(*) AS c " +
                    "FROM bike_parking bp " +
                    "JOIN share_bike sb ON sb.bike_id = bp.bike_id " +
                    "WHERE bp.current_port_id = ? AND sb.status = 'docked'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt("c");
            }
        }
    }

    private Integer readCapacity(Connection conn, int portId) throws SQLException {
        String sql = "SELECT capacity FROM port_information WHERE port_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getInt("capacity");
            }
        }
    }

    // GET: 一覧表示
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // ===== セッション認証チェック =====
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("operatorId") == null) {
            response.sendRedirect(request.getContextPath() + "/adminlogin");
            return;
        }

        Integer sessionOperatorId = (Integer) session.getAttribute("operatorId");
        String sessionOperatorName = (String) session.getAttribute("operatorName");

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String q = safe(request.getParameter("q"), "").trim();          // ポート名検索
        String sort = safe(request.getParameter("sort"), "id");       // 並び順
        String msg = safe(request.getParameter("msg"), "");             // 成功/失敗メッセージ

        Integer opId = null;
        String path = request.getPathInfo();
        if (path != null && !path.equals("/")) {
            String s = path.replace("/", "");
            if (s.matches("\\d+")) opId = Integer.valueOf(s);
        }

        // ★★★ セッションユーザが特定の事業者に絞っている場合の検証
        if (opId != null && !opId.equals(sessionOperatorId)) {
            // 他の事業者にアクセスしようとしているので、セッションの事業者に強制
            opId = sessionOperatorId;
        }
        // セッション有効な場合は、必ずセッションの事業者を使用
        opId = sessionOperatorId;

        String ctx = request.getContextPath();
        String basePath = ctx + "/portadmin" + (opId != null ? ("/" + opId) : "");



        // sort はホワイトリストで安全に
        String orderBy;
        switch (sort) {
            case "id":         orderBy = "port_id"; break;
            case "name":       orderBy = "port_name, operator_name"; break;
            case "bikes_desc": orderBy = "bikes DESC, operator_name, port_name"; break;
            case "bikes_asc":  orderBy = "bikes ASC, operator_name, port_name";  break;
            case "free_desc":  orderBy = "free_docks DESC, operator_name, port_name"; break;
            case "free_asc":   orderBy = "free_docks ASC, operator_name, port_name";  break;
            default:           orderBy = "port_id"; break;
        }

        List<PortRow> rows = new ArrayList<>();

        // port_status を読む（routesearch と同じ）
        String sql =
            "SELECT port_id, operator_id, operator_name, port_name, bikes, free_docks " +
            "FROM v_port_status " +
            "WHERE 1=1 " +
            (opId != null ? " AND operator_id = ? " : "") +
            " AND (? = '' OR port_name ILIKE ?) " +
            "ORDER BY " + orderBy;

        try (Connection conn = DatabaseConfig.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            if (opId != null) ps.setInt(idx++, opId);
            ps.setString(idx++, q);
            ps.setString(idx++, "%" + q + "%");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PortRow(
                        rs.getInt("port_id"),
                        rs.getString("operator_name"),
                        rs.getString("port_name"),
                        rs.getInt("bikes"),
                        rs.getInt("free_docks")
                    ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            out.println("<h1>DBエラー: " + esc(e.getMessage()) + "</h1>");
            return;
        }

        // ---- HTML ----
        out.println("<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\"/>");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        out.println("<title>ShareCycle Admin</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + ctx + "/static/app.css\"/>");
        out.println("</head><body class=\"page-port-admin\"><div class=\"app\">");

        out.println("<div class=\"card\">");

        out.println("<div class=\"header\">");
        out.println("<div class=\"header-left\">");
        out.println("<h1 class=\"title\">シェアサイクル管理 (ポート一覧) </h1>");
        out.println("<p class=\"muted\">事業者: " + esc(sessionOperatorName) + "</p>");
        out.println("<p class=\"muted\">各ポートの自転車台数 (bikes) と空き (free_docks)</p>");
        out.println("</div>");

        out.println("<div class=\"header-actions\">");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/adminlogout\">ログアウト</a>");
        out.println("<a class=\"btn2\"  href=\"" + ctx + "/routesearch\">ルート検索に戻る</a>");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portlog\">配車ログ</a>");
        out.println("</div>");
        out.println("</div>"); // header

        out.println("<div class=\"row\">");

        if (!msg.isEmpty()) {
            out.println("<div class=\"alert\">" + esc(msg) + "</div>");
        }

        // 検索・ソート
        out.println("<form method=\"GET\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");
        out.println("<input type=\"text\" name=\"q\" placeholder=\"ポート名で検索\" value=\"" + esc(q) + "\"/>");
        out.println("<select name=\"sort\">");
        out.println("<option value=\"id\"" + ("id".equals(sort) ? " selected" : "") + ">ID順</option>");
        out.println("<option value=\"name\"" + ("name".equals(sort) ? " selected" : "") + ">名前順</option>");
        out.println("<option value=\"bikes_desc\"" + ("bikes_desc".equals(sort) ? " selected" : "") + ">bikes 多い順</option>");
        out.println("<option value=\"bikes_asc\"" + ("bikes_asc".equals(sort) ? " selected" : "") + ">bikes 少ない順</option>");
        out.println("<option value=\"free_desc\"" + ("free_desc".equals(sort) ? " selected" : "") + ">空き 多い順</option>");
        out.println("<option value=\"free_asc\"" + ("free_asc".equals(sort) ? " selected" : "") + ">空き 少ない順</option>");
        out.println("</select>");
        out.println("<button class=\"btn\" type=\"submit\">表示</button>");
        out.println("</div>");
        out.println("</form>");

        out.println("<hr/>");

        out.println("<div class=\"row mt-14\">");
        out.println("<div class=\"alert alert-warn\">\"配車 : 事業者による自転車の移動を記録できます<br/></div>");
        out.println("</div>");
        out.println("<form method=\"POST\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");
        out.println("<input type=\"hidden\" name=\"action\" value=\"move\"/>");


        out.println("<input type=\"hidden\" name=\"q\" value=\"" + esc(q) + "\"/>");
        out.println("<input type=\"hidden\" name=\"sort\" value=\"" + esc(sort) + "\"/>");

        out.println("<input class=\"w-160\" type=\"number\" name=\"from_port_id\" placeholder=\"from_port_id\" required/>");
        out.println("<input class=\"w-160\" type=\"number\" name=\"to_port_id\" placeholder=\"to_port_id\" required/>");
        out.println("<input class=\"w-120\" type=\"number\" name=\"moved_bikes\" min=\"1\" value=\"1\" required/>");

        out.println("<button class=\"btn\" type=\"submit\">移動記録</button>");

        out.println("</div>");
        out.println("</form>");

        out.println("<hr/>");

        out.println("<h2>ポート一覧 (" + rows.size() + "件)</h2>");

        out.println("<div class=\"table-wrap\">");
        out.println("<table>");
        out.println("<tr>");
        out.println("<th>operator</th><th>port_id</th><th>port_name</th><th>bikes</th><th>free_docks</th>");
        out.println("</tr>");

        for (PortRow r : rows) {
            out.println("<tr>");
            out.println("<td>" + esc(r.operatorName) + "</td>");
            out.println("<td>" + r.portId + "</td>");
            out.println("<td>" + esc(r.portName) + "</td>");
            out.println("<td>" + r.bikes + "</td>");
            out.println("<td>" + r.freeDocks + "</td>");

            out.println("</tr>");
        }

        out.println("</table>");
        out.println("</div>");
        out.println("</div></div></body></html>");
    }

    // POST: bikes/free_docks を更新（任意）
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // ===== セッション認証チェック =====
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("operatorId") == null) {
            response.sendRedirect(request.getContextPath() + "/adminlogin");
            return;
        }

        Integer sessionOperatorId = (Integer) session.getAttribute("operatorId");

        request.setCharacterEncoding("UTF-8");
        String action = safe(request.getParameter("action"), "update");

        // ★ doPost側でも opId / basePath を作る
        Integer opId = sessionOperatorId;  // セッション認証済みなので、常にセッションの operatorId を使用

        String ctx = request.getContextPath();
        String basePath = ctx + "/portadmin" + (opId != null ? ("/" + opId) : "");

        // ★ 更新後も検索条件を維持
        String q = safe(request.getParameter("q"), "").trim();
        String sort = safe(request.getParameter("sort"), "name");
        String keep = "q=" + URLEncoder.encode(q, "UTF-8")
                    + "&sort=" + URLEncoder.encode(sort, "UTF-8");



        if ("move".equals(action)) {
            String fromStr = request.getParameter("from_port_id");
            String toStr   = request.getParameter("to_port_id");
            String mStr    = request.getParameter("moved_bikes");

            int fromId, toId, moved;
            try {
                fromId = Integer.parseInt(fromStr);
                toId   = Integer.parseInt(toStr);
                moved  = Integer.parseInt(mStr);
                if (moved <= 0) throw new NumberFormatException("moved<=0");
                if (fromId == toId) throw new NumberFormatException("same port");
            } catch (Exception e) {
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("入力が不正です（port_id/台数を確認）", "UTF-8"));
                return;
            }

            String moveBikesSql =
                "WITH picked AS ( " +
                "  SELECT bp.bike_id " +
                "  FROM bike_parking bp " +
                "  JOIN share_bike sb ON sb.bike_id = bp.bike_id " +
                "  WHERE bp.current_port_id = ? AND sb.status = 'docked' " +
                "  ORDER BY bp.bike_id " +
                "  LIMIT ? " +
                "  FOR UPDATE " +
                ") " +
                "UPDATE bike_parking bp " +
                "SET current_port_id = ?, parked_at = CURRENT_TIMESTAMP " +
                "FROM picked " +
                "WHERE bp.bike_id = picked.bike_id";

            // 移動ログを3テーブルに分割挿入
            String insertRecord = "INSERT INTO move_record(moved_bikes, source) VALUES(?, ?) RETURNING log_id";
            String insertFrom = "INSERT INTO move_from(log_id, from_port_id) VALUES(?, ?)";
            String insertTo = "INSERT INTO move_to(log_id, to_port_id) VALUES(?, ?)";

            Connection conn = null;
            try {
                conn = DatabaseConfig.getConnection();
                conn.setAutoCommit(false);

                // デッドロック回避：小さいport_idからロック
                int a = Math.min(fromId, toId);
                int b = Math.max(fromId, toId);

                Integer opA = readOperatorIdForUpdate(conn, a);
                Integer opB = readOperatorIdForUpdate(conn, b);

                if (opA == null || opB == null) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("port_id が見つかりません", "UTF-8"));
                    return;
                }

                Integer fromOp = (fromId == a) ? opA : opB;
                Integer toOp   = (toId == a) ? opA : opB;

                if (!fromOp.equals(toOp)) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("別operator間の移動は不可です", "UTF-8"));
                    return;
                }

                int bikesFrom = countBikesAtPort(conn, fromId);
                int bikesTo   = countBikesAtPort(conn, toId);
                Integer capTo = readCapacity(conn, toId);

                if (capTo == null) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("移動先portのcapacityが見つかりません", "UTF-8"));
                    return;
                }

                int freeTo = capTo - bikesTo;

                if (bikesFrom < moved) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("移動元の在庫が足りません（bikes不足）", "UTF-8"));
                    return;
                }
                if (freeTo < moved) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("移動先の空きが足りません（free_docks不足）", "UTF-8"));
                    return;
                }

                // 自転車を移動
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(moveBikesSql)) {
                    ps.setInt(1, fromId);
                    ps.setInt(2, moved);
                    ps.setInt(3, toId);
                    updated = ps.executeUpdate();
                }

                if (updated != moved) {
                    conn.rollback();
                    response.sendRedirect(basePath + "?" + keep + "&msg=" +
                            URLEncoder.encode("移動に失敗しました（更新台数が足りない）", "UTF-8"));
                    return;
                }

                // ログを3テーブルに記録
                long logId;
                try (PreparedStatement ps = conn.prepareStatement(insertRecord)) {
                    ps.setInt(1, moved);
                    ps.setString(2, "admin");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("log_id取得失敗");
                        logId = rs.getLong("log_id");
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(insertFrom)) {
                    ps.setLong(1, logId);
                    ps.setInt(2, fromId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(insertTo)) {
                    ps.setLong(1, logId);
                    ps.setInt(2, toId);
                    ps.executeUpdate();
                }

                conn.commit();
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("移動しました: " + fromId + " → " + toId + " (" + moved + "台)", "UTF-8"));
                return;

            } catch (Exception e) {
                if (conn != null) {
                    try { conn.rollback(); } catch (SQLException ignore) {}
                }
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("DBエラー(移動): " + e.getMessage(), "UTF-8"));
                return;
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (SQLException ignore) {}
                }
            }
        }

        String portIdStr = request.getParameter("port_id");
        String bikesStr = request.getParameter("bikes");
        String freeStr  = request.getParameter("free_docks");

        int portId, bikes, free;
        try {
            portId = Integer.parseInt(portIdStr);
            bikes  = Integer.parseInt(bikesStr);
            free   = Integer.parseInt(freeStr);
            if (bikes < 0 || free < 0) throw new NumberFormatException("negative");
        } catch (Exception e) {
            response.sendRedirect(basePath + "?" + keep + "&msg=" +
                    URLEncoder.encode("入力が不正です（数値を入れてね）", "UTF-8"));
            return;
        }

        String updateSql = "UPDATE port_operation SET bikes = ?, free_docks = ? WHERE port_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
            PreparedStatement ps = conn.prepareStatement(updateSql)) {

            ps.setInt(1, bikes);
            ps.setInt(2, free);
            ps.setInt(3, portId);

            int n = ps.executeUpdate();
            if (n == 0) {
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("更新できませんでした（port_id が見つからない）", "UTF-8"));
            } else {
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("更新しました（port_id=" + portId + "）", "UTF-8"));
            }
        } catch (Exception e) {
            response.sendRedirect(basePath + "?" + keep + "&msg=" +
                    URLEncoder.encode("DBエラー: " + e.getMessage(), "UTF-8"));
        }
    }
}

