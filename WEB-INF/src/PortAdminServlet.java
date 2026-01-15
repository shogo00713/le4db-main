import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")



public class PortAdminServlet extends HttpServlet {

    private String _hostname = null;
    private String _dbname   = null;
    private String _username = null;
    private String _password = null;

    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext()
                .getRealPath("WEB-INF/le4db.ini");

        try (FileInputStream fis = new FileInputStream(iniFilePath)) {
            Properties prop = new Properties();
            prop.load(fis);
            _hostname = prop.getProperty("hostname");
            _dbname   = prop.getProperty("dbname");
            _username = prop.getProperty("username");
            _password = prop.getProperty("password");

            Class.forName("org.postgresql.Driver");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Connection openConn() throws SQLException {
        return DriverManager.getConnection(
            "jdbc:postgresql://" + _hostname + ":5432/" + _dbname,
            _username, _password
        );
    }

    // HTMLエスケープ
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String safe(String s, String def) {
        return (s == null) ? def : s;
    }

    private static class PortRow {
        final int portId;
        final int operatorId;
        final String operatorName;
        final String portName;
        final int bikes;
        final int freeDocks;

        PortRow(int portId, int operatorId, String operatorName, String portName, int bikes, int freeDocks) {
            this.portId = portId;
            this.operatorId = operatorId;
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
        String sql = "SELECT COUNT(*) AS c FROM share_bike WHERE current_port_id = ?";
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

        String ctx = request.getContextPath();
        String basePath = ctx + "/portadmin" + (opId != null ? ("/" + opId) : "");



        // sort はホワイトリストで安全に
        String orderBy;
        switch (sort) {
            case "id":        orderBy = "port_id"; break;
            case "name":      orderBy = "port_name, operator_name"; break;
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
            "FROM port_status " +
            "WHERE 1=1 " +
            (opId != null ? " AND operator_id = ? " : "") +
            " AND (? = '' OR port_name ILIKE ?) " +
            "ORDER BY " + orderBy;

        try (Connection conn = openConn();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            if (opId != null) ps.setInt(idx++, opId);
            ps.setString(idx++, q);
            ps.setString(idx++, "%" + q + "%");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PortRow(
                        rs.getInt("port_id"),
                        rs.getInt("operator_id"),
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
        
        // CSS（簡易）
        out.println("<style>");
        out.println("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Noto Sans JP','Hiragino Kaku Gothic ProN',Meiryo,sans-serif;margin:0;background:#f5f5f7;color:#1d1d1f;}");
        out.println(".app{max-width:1100px;margin:24px auto;padding:0 16px;}");
        out.println(".card{background:#fff;border:1px solid rgba(0,0,0,.10);border-radius:16px;box-shadow:0 10px 28px rgba(0,0,0,.08);padding:16px;}");
        out.println(".title{margin:0 0 6px 0;font-size:20px;}");
        out.println(".muted{color:#6e6e73;font-size:13px;margin:0 0 14px 0;}");
        out.println(".row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:12px 0;}");
        out.println("input,select{padding:10px 12px;border:1px solid rgba(0,0,0,.12);border-radius:12px;font-size:14px;}");
        out.println(".btn,.btn2{display:inline-block;text-decoration:none;}");
        out.println(".header{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;}");
        out.println(".header-left{min-width:0;}");
        out.println(".header-actions{display:flex;flex-direction:column;gap:10px;align-items:flex-end;margin-left:auto;}");
        out.println(".header-actions a{white-space:nowrap;}");
        out.println(".alert{padding:10px 12px;border-radius:12px;background:#ecfeff;border:1px solid rgba(14,116,144,.25);color:#0e7490;margin:10px 0;}");
        out.println("table{width:100%;border-collapse:separate;border-spacing:0;min-width:780px;}");
        out.println("th,td{padding:10px 12px;border-bottom:1px solid rgba(0,0,0,.06);text-align:left;white-space:nowrap;}");
        out.println("th{background:rgba(250,250,252,.98);font-size:12px;color:#3a3a3c;position:sticky;top:0;}");
        out.println(".table-wrap{overflow:auto;border:1px solid rgba(0,0,0,.08);border-radius:14px;}");
        out.println(".mini{font-size:12px;color:#6e6e73;}");
        out.println("a{color:inherit;text-decoration:none;}");
        out.println("a:visited{color:inherit;}");
        out.println("a.btn, a.btn:visited{color:#fff;}");
        out.println("a.btn2, a.btn2:visited{color:#111;}");
        out.println(".btn{display:inline-block;}");
        out.println(".btn2{display:inline-block;}");
        out.println(".btn:hover{filter:brightness(0.95);}");
        out.println(".btn2:hover{background:#f2f2f2;}");
        out.println("</style>");
        // CSSここまで


        out.println("<div class=\"card\">");

        out.println("<div class=\"header\">");
        out.println("<div class=\"header-left\">");
        out.println("<h1 class=\"title\">シェアサイクル管理（ポート一覧）</h1>");
        out.println("<p class=\"muted\">各ポートの自転車台数 (bikes) と空き (free_docks)</p>");
        out.println("</div>");

        out.println("<div class=\"header-actions\">");
        out.println("<a class=\"btn2\"  href=\"" + ctx + "/routesearch\">ルート検索に戻る</a>");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portlog\">配車ログ</a>");
        out.println("</div>");
        out.println("</div>"); // header

        // ★ここでは out.println("</div>"); しない！！（カード閉じない）



        // operator 切替ボタン（q/sort を維持）
        out.println("<div class=\"row\">");

        // 全事業者
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portadmin/?q=" + URLEncoder.encode(q, "UTF-8")
                + "&sort=" + URLEncoder.encode(sort, "UTF-8") + "\">全事業者</a>");

        String opSql = "SELECT DISTINCT operator_id, operator_name FROM port_status ORDER BY operator_name";
        try (Connection conn = openConn();
            PreparedStatement ps = conn.prepareStatement(opSql);
            ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int oid = rs.getInt("operator_id");
                String on = rs.getString("operator_name");

                String href = ctx + "/portadmin/" + oid
                        + "?q=" + URLEncoder.encode(q, "UTF-8")
                        + "&sort=" + URLEncoder.encode(sort, "UTF-8");

                // 選択中だけちょい強調（btn でもOK）
                String cls = (opId != null && opId == oid) ? "btn" : "btn2";
                out.println("<a class=\"" + cls + "\" href=\"" + href + "\">" + esc(on) + "</a>");
            }
        } catch (Exception e) {
            out.println("<span class='mini'>operator一覧の取得に失敗</span>");
        }

        out.println("</div>");

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

        // --- 配車（移動）フォーム ---
        out.println("<div class=\"row\" style=\"margin-top:14px;\">");
        out.println("<div class=\"alert\" style=\"background:#fff7ed;border-color:rgba(245,158,11,.28);color:#92400e;\">"
                + "配車 : UPDATE × 2 と ログの INSERT をトランザクションで実行"
                + "</div>");
        out.println("</div>");

        // ★ちゃんとフォーム開始！
        out.println("<form method=\"POST\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");

        // ★move を送る（ここが重要）
        out.println("<input type=\"hidden\" name=\"action\" value=\"move\"/>");

        // ★条件維持
        out.println("<input type=\"hidden\" name=\"q\" value=\"" + esc(q) + "\"/>");
        out.println("<input type=\"hidden\" name=\"sort\" value=\"" + esc(sort) + "\"/>");

        out.println("<input type=\"number\" name=\"from_port_id\" placeholder=\"from_port_id\" required style=\"width:160px;\"/>");
        out.println("<input type=\"number\" name=\"to_port_id\" placeholder=\"to_port_id\" required style=\"width:160px;\"/>");
        out.println("<input type=\"number\" name=\"moved_bikes\" min=\"1\" value=\"1\" required style=\"width:120px;\"/>");

        out.println("<button class=\"btn\" type=\"submit\">移動実行</button>");
        out.println("<span class=\"mini\">※同一operator内のみ・在庫/空きチェックあり</span>");

        out.println("</div>");
        out.println("</form>");


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

        out.println("<p class=\"mini\" style=\"margin-top:12px;\">※ 更新ボタンは port_operation を UPDATE する想定（下の doPost）。不要なら消してOK。</p>");

        out.println("</div></div></body></html>");
    }

    // POST: bikes/free_docks を更新（任意）
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        String action = safe(request.getParameter("action"), "update");

        // ★ doPost側でも opId / basePath を作る
        Integer opId = null;
        String path = request.getPathInfo();
        if (path != null && !path.equals("/")) {
            String s = path.replace("/", "");
            if (s.matches("\\d+")) opId = Integer.valueOf(s);
        }

        String ctx = request.getContextPath();
        String basePath = ctx + "/portadmin" + (opId != null ? ("/" + opId) : "");

        // ★ 更新後も検索条件を維持
        String q = safe(request.getParameter("q"), "").trim();
        String sort = safe(request.getParameter("sort"), "name");
        String keep = "q=" + URLEncoder.encode(q, "UTF-8")
                    + "&sort=" + URLEncoder.encode(sort, "UTF-8");

        // =========================
        // 1) move を先に処理する！
        // =========================
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

            // moved台ぶんだけ、fromの自転車をtoへ移す（主キー不明でも動くようにctidを使う）
            String moveBikesSql =
                "WITH picked AS ( " +
                "  SELECT ctid FROM share_bike " +
                "  WHERE current_port_id = ? " +
                "  ORDER BY ctid " +
                "  LIMIT ? " +
                "  FOR UPDATE " +
                ") " +
                "UPDATE share_bike sb " +
                "SET current_port_id = ? " +
                "FROM picked " +
                "WHERE sb.ctid = picked.ctid";

            String insertLog =
                "INSERT INTO bike_move_log(operator_id, from_port_id, to_port_id, moved_bikes) VALUES(?,?,?,?)";

            try (Connection conn = openConn()) {
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

                try (PreparedStatement ps3 = conn.prepareStatement(insertLog)) {
                    ps3.setInt(1, fromOp);
                    ps3.setInt(2, fromId);
                    ps3.setInt(3, toId);
                    ps3.setInt(4, moved);
                    ps3.executeUpdate();
                }

                conn.commit();
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("移動しました: " + fromId + " → " + toId + " (" + moved + "台)", "UTF-8"));
                return;

            } catch (Exception e) {
                response.sendRedirect(basePath + "?" + keep + "&msg=" +
                        URLEncoder.encode("DBエラー(移動): " + e.getMessage(), "UTF-8"));
                return;
            }
        }


        // =========================
        // 2) それ以外は update（任意更新）
        // =========================
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

        try (Connection conn = openConn();
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

