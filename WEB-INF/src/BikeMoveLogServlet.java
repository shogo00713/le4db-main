import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class BikeMoveLogServlet extends HttpServlet {

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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
    private String safe(String s, String def) { return (s == null) ? def : s; }

    private static class LogRow {
        final int logId;
        final String movedAt;
        final int operatorId;
        final String operatorName;
        final int fromPortId;
        final String fromName;
        final int toPortId;
        final String toName;
        final int movedBikes;
        final String source;

        LogRow(int logId, String movedAt, int operatorId, String operatorName,
               int fromPortId, String fromName, int toPortId, String toName, int movedBikes, String source) {
            this.logId = logId;
            this.movedAt = movedAt;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.fromPortId = fromPortId;
            this.fromName = fromName;
            this.toPortId = toPortId;
            this.toName = toName;
            this.movedBikes = movedBikes;
            this.source = source;
        }
    }

    // GET: ログ一覧 + 検索 + 集約
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // ===== セッション認証チェック =====
        javax.servlet.http.HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("operatorId") == null) {
            response.sendRedirect(request.getContextPath() + "/adminlogin");
            return;
        }

        Integer sessionOperatorId = (Integer) session.getAttribute("operatorId");
        String sessionOperatorName = (String) session.getAttribute("operatorName");

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String q = safe(request.getParameter("q"), "").trim();           // port名検索
        String msg = safe(request.getParameter("msg"), "");

        // セッション認証済みなので、常にセッションの事業者IDを使用
        int opId = sessionOperatorId;

        String ctx = request.getContextPath();
        String basePath = ctx + "/portlog";

        // ---- ログ一覧（SELECT + JOIN）----
        List<LogRow> rows = new ArrayList<>();

        String listSql =
            "SELECT " +
            "  l.log_id, to_char(l.moved_at, 'YYYY-MM-DD HH24:MI:SS') AS moved_at, " +
            "  l.operator_id, COALESCE(op.operator_name, '(unknown)') AS operator_name, " +
            "  l.from_port_id, COALESCE(pf.port_name, '(unknown)') AS from_name, " +
            "  l.to_port_id, COALESCE(pt.port_name, '(unknown)') AS to_name, " +
            "  l.moved_bikes, l.source " +
            "FROM bike_move_log l " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op " +
            "  ON op.operator_id = l.operator_id " +
            "LEFT JOIN port_information pf ON pf.port_id = l.from_port_id " +
            "LEFT JOIN port_information pt ON pt.port_id = l.to_port_id " +
            "WHERE (? = -1 OR l.operator_id = ?) " +
            "  AND (? = '' OR pf.port_name ILIKE ? OR pt.port_name ILIKE ?) " +
            "ORDER BY l.moved_at DESC " +
            "LIMIT 80";

        // ---- 集約（GROUP BY / COUNT / SUM）----
        // operatorごとの移動回数＆合計台数（全ログ）
        class Agg { int operatorId; String operatorName; int cnt; int sum; }
        List<Agg> aggs = new ArrayList<>();

        String aggSql =
            "SELECT l.operator_id, COALESCE(op.operator_name,'(unknown)') AS operator_name, " +
            "       COUNT(*) AS cnt, SUM(l.moved_bikes) AS sum_bikes " +
            "FROM bike_move_log l " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op " +
            "  ON op.operator_id = l.operator_id " +
            "WHERE l.operator_id = ? " +
            "GROUP BY l.operator_id, op.operator_name " +
            "ORDER BY sum_bikes DESC, cnt DESC " +
            "LIMIT 10";

        // ---- ユーザー利用のみの分析（source='user'）----
        class PortStat { int portId; String portName; int operatorId; String operatorName; int total; int trips; }
        List<PortStat> topDepartures = new ArrayList<>();
        List<PortStat> topReturns = new ArrayList<>();
        List<PortStat> leastUsed = new ArrayList<>();

        String topDepartSql =
            "SELECT l.from_port_id AS port_id, COALESCE(p.port_name,'(unknown)') AS port_name, " +
            "       l.operator_id, COALESCE(op.operator_name,'(unknown)') AS operator_name, " +
            "       SUM(l.moved_bikes) AS total_bikes, COUNT(*) AS trips " +
            "FROM bike_move_log l " +
            "LEFT JOIN port_information p ON p.port_id = l.from_port_id " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op ON op.operator_id = l.operator_id " +
            "WHERE l.source = 'user' AND (? = -1 OR l.operator_id = ?) " +
            "GROUP BY l.from_port_id, p.port_name, l.operator_id, op.operator_name " +
            "ORDER BY total_bikes DESC, trips DESC " +
            "LIMIT 3";

        String topReturnSql =
            "SELECT l.to_port_id AS port_id, COALESCE(p.port_name,'(unknown)') AS port_name, " +
            "       l.operator_id, COALESCE(op.operator_name,'(unknown)') AS operator_name, " +
            "       SUM(l.moved_bikes) AS total_bikes, COUNT(*) AS trips " +
            "FROM bike_move_log l " +
            "LEFT JOIN port_information p ON p.port_id = l.to_port_id " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op ON op.operator_id = l.operator_id " +
            "WHERE l.source = 'user' AND (? = -1 OR l.operator_id = ?) " +
            "GROUP BY l.to_port_id, p.port_name, l.operator_id, op.operator_name " +
            "ORDER BY total_bikes DESC, trips DESC " +
            "LIMIT 3";

        String leastUsedSql =
            "SELECT po.port_id, COALESCE(pi.port_name,'(unknown)') AS port_name, po.operator_id, " +
            "       COALESCE(op.operator_name,'(unknown)') AS operator_name, COALESCE(usage.total_bikes,0) AS total_bikes, " +
            "       COALESCE(usage.trips,0) AS trips " +
            "FROM port_operation po " +
            "JOIN port_information pi ON pi.port_id = po.port_id " +
            "LEFT JOIN ( " +
            "  SELECT t.port_id, t.operator_id, SUM(t.moved_bikes) AS total_bikes, COUNT(*) AS trips FROM ( " +
            "    SELECT l.from_port_id AS port_id, l.operator_id, l.moved_bikes FROM bike_move_log l WHERE l.source='user' " +
            "    UNION ALL " +
            "    SELECT l.to_port_id   AS port_id, l.operator_id, l.moved_bikes FROM bike_move_log l WHERE l.source='user' " +
            "  ) t GROUP BY t.port_id, t.operator_id " +
            ") usage ON usage.port_id = po.port_id AND usage.operator_id = po.operator_id " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op ON op.operator_id = po.operator_id " +
            "WHERE (? = -1 OR po.operator_id = ?) " +
            "ORDER BY total_bikes ASC, po.port_id ASC " +
            "LIMIT 3";

        try (Connection conn = openConn()) {

            try (PreparedStatement ps = conn.prepareStatement(listSql)) {
                int idx = 1;
                ps.setInt(idx++, opId);
                ps.setInt(idx++, opId);
                ps.setString(idx++, q);
                ps.setString(idx++, "%" + q + "%");
                ps.setString(idx++, "%" + q + "%");

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new LogRow(
                            rs.getInt("log_id"),
                            rs.getString("moved_at"),
                            rs.getInt("operator_id"),
                            rs.getString("operator_name"),
                            rs.getInt("from_port_id"),
                            rs.getString("from_name"),
                            rs.getInt("to_port_id"),
                            rs.getString("to_name"),
                            rs.getInt("moved_bikes"),
                            rs.getString("source")
                        ));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(aggSql)) {
                ps.setInt(1, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Agg a = new Agg();
                        a.operatorId = rs.getInt("operator_id");
                        a.operatorName = rs.getString("operator_name");
                        a.cnt = rs.getInt("cnt");
                        a.sum = rs.getInt("sum_bikes");
                        aggs.add(a);
                    }
                }
            }

            // ユーザー利用のみ: 出発上位
            try (PreparedStatement ps = conn.prepareStatement(topDepartSql)) {
                ps.setInt(1, opId);
                ps.setInt(2, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PortStat s = new PortStat();
                        s.portId = rs.getInt("port_id");
                        s.portName = rs.getString("port_name");
                        s.operatorId = rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
                        s.total = rs.getInt("total_bikes");
                        s.trips = rs.getInt("trips");
                        topDepartures.add(s);
                    }
                }
            }

            // ユーザー利用のみ: 返却上位
            try (PreparedStatement ps = conn.prepareStatement(topReturnSql)) {
                ps.setInt(1, opId);
                ps.setInt(2, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PortStat s = new PortStat();
                        s.portId = rs.getInt("port_id");
                        s.portName = rs.getString("port_name");
                        s.operatorId = rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
                        s.total = rs.getInt("total_bikes");
                        s.trips = rs.getInt("trips");
                        topReturns.add(s);
                    }
                }
            }

            // ユーザー利用のみ: 未使用寄り（合計が少ない）
            try (PreparedStatement ps = conn.prepareStatement(leastUsedSql)) {
                ps.setInt(1, opId);
                ps.setInt(2, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PortStat s = new PortStat();
                        s.portId = rs.getInt("port_id");
                        s.portName = rs.getString("port_name");
                        s.operatorId = rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
                        s.total = rs.getInt("total_bikes");
                        s.trips = rs.getInt("trips");
                        leastUsed.add(s);
                    }
                }
            }

        } catch (Exception e) {
            out.println("<h1>DBエラー: " + esc(e.getMessage()) + "</h1>");
            e.printStackTrace();
            return;
        }

        // ---- HTML ----
        out.println("<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\"/>");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        out.println("<title>Bike Move Log</title>");
        out.println("<style>");
        out.println("*{margin:0;padding:0;box-sizing:border-box;}");
        out.println("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Noto Sans JP','Hiragino Sans','Hiragino Kaku Gothic ProN',Meiryo,sans-serif;background:#eff6ff;color:#2d3748;padding:20px;}");
        out.println(".app{max-width:1200px;margin:0 auto;}");
        out.println(".card{background:#fff;border-radius:20px;box-shadow:0 10px 30px rgba(0,0,0,0.08);padding:32px;margin-bottom:24px;}");
        out.println(".title{margin:0 0 8px 0;font-size:28px;font-weight:700;color:#1a202c;letter-spacing:-0.5px;}");
        out.println(".muted{color:#718096;font-size:14px;margin:4px 0;}");
        out.println(".row{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin:16px 0;}");
        out.println("input,select{padding:12px 16px;border:2px solid #e2e8f0;border-radius:12px;font-size:14px;transition:all 0.2s ease;background:#f7fafc;}");
        out.println("input:focus,select:focus{outline:none;border-color:#3b82f6;background:#fff;box-shadow:0 0 0 3px rgba(59,130,246,0.1);}");
        out.println(".btn{padding:12px 24px;background:linear-gradient(135deg,#3b82f6 0%,#1d4ed8 100%);color:#fff;border:none;border-radius:12px;font-size:14px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-block;transition:all 0.3s ease;box-shadow:0 4px 12px rgba(59,130,246,0.3);}");
        out.println(".btn:hover{transform:translateY(-2px);box-shadow:0 6px 16px rgba(59,130,246,0.4);}");
        out.println(".btn2{padding:10px 20px;background:#fff;color:#4a5568;border:2px solid #e2e8f0;border-radius:10px;font-size:14px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-block;transition:all 0.2s ease;}");
        out.println(".btn2:hover{background:#dbeafe;border-color:#3b82f6;transform:translateY(-1px);}");
        out.println(".alert{padding:14px 18px;border-radius:12px;background:#fff5f5;border:2px solid #feb2b2;color:#742a2a;margin:16px 0;font-size:14px;}");
        out.println(".table-wrap{overflow:auto;border-radius:16px;border:2px solid #e2e8f0;margin:20px 0;}");
        out.println("table{width:100%;border-collapse:separate;border-spacing:0;}");
        out.println("th,td{padding:14px 16px;text-align:left;}");
        out.println("th{background:#f7fafc;font-size:13px;font-weight:700;color:#4a5568;text-transform:uppercase;letter-spacing:0.5px;border-bottom:2px solid #e2e8f0;}");
        out.println("td{border-bottom:1px solid #e2e8f0;color:#2d3748;}");
        out.println("tr:hover td{background:#eff6ff;}");
        out.println(".mini{font-size:13px;color:#718096;}");
        out.println("</style>");
        out.println("</head><body><div class=\"app\"><div class=\"card\">");

        out.println("<div class=\"row\" style=\"justify-content:space-between;\">");
        out.println("<div>");
        out.println("<h1 class=\"title\">配車ログ（bike_move_log）</h1>");
        out.println("<p class=\"muted\">事業者: " + esc(sessionOperatorName) + "</p>");
        out.println("<p class=\"muted\">検索(SELECT+JOIN) / 集約(GROUP BY) をここでデモできます</p>");
        out.println("</div>");
        out.println("<div class=\"row\">");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portadmin/\">ポート管理へ戻る</a>");
        out.println("</div>");
        out.println("</div>");

        if (!msg.isEmpty()) out.println("<div class=\"alert\">" + esc(msg) + "</div>");

        // 分析結果（ユーザー利用のみ）
        out.println("<div class=\"row\">");
        out.println("<div class=\"alert\" style=\"background:#ecfeff;border-color:rgba(14,165,233,.28);color:#0c4a6e;\">");
        out.println("<b>分析（ユーザー利用のみ）</b>：事業者の活用検討に役立つサマリ<br/>");
        out.println("<div class=\"row\" style=\"gap:24px;\">");
        // 出発上位
        out.println("<div><span class=\"mini\"><b>出発が多いポート TOP3</b></span><br/>");
        for (PortStat s : topDepartures) {
            out.println(esc(s.portName) + " <span class=\"mini\">(#" + s.portId + ")</span> - "
                + esc(s.operatorName) + "：合計 " + s.total + "台 / " + s.trips + "回<br/>");
        }
        out.println("</div>");
        // 返却上位
        out.println("<div><span class=\"mini\"><b>返却が多いポート TOP3</b></span><br/>");
        for (PortStat s : topReturns) {
            out.println(esc(s.portName) + " <span class=\"mini\">(#" + s.portId + ")</span> - "
                + esc(s.operatorName) + "：合計 " + s.total + "台 / " + s.trips + "回<br/>");
        }
        out.println("</div>");
        // 未使用寄り
        out.println("<div><span class=\"mini\"><b>未使用寄りポート TOP3</b></span><br/>");
        for (PortStat s : leastUsed) {
            out.println(esc(s.portName) + " <span class=\"mini\">(#" + s.portId + ")</span> - "
                + esc(s.operatorName) + "：合計 " + s.total + "台 / " + s.trips + "回<br/>");
        }
        out.println("</div>");
        out.println("</div>");
        out.println("</div>");

        // 検索フォーム
        out.println("<form method=\"GET\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");
        out.println("<input type=\"text\" name=\"q\" placeholder=\"ポート名で検索（例: 京都駅）\" value=\"" + esc(q) + "\"/>");
        out.println("<button class=\"btn\" type=\"submit\">検索</button>");
        out.println("</div>");
        out.println("</form>");

        // ログ表
        out.println("<div class=\"table-wrap\"><table>");
        out.println("<tr>");
        out.println("<th>log_id</th><th>日時</th><th>operator</th><th>from</th><th>to</th><th>台数</th><th>種別</th>");
        out.println("</tr>");

        for (LogRow r : rows) {
            out.println("<tr>");
            out.println("<td>" + r.logId + "</td>");
            out.println("<td>" + esc(r.movedAt) + "</td>");
            out.println("<td>" + esc(r.operatorName) + " <span class=\"mini\">(#" + r.operatorId + ")</span></td>");
            out.println("<td>" + esc(r.fromName) + " <span class=\"mini\">(" + r.fromPortId + ")</span></td>");
            out.println("<td>" + esc(r.toName) + " <span class=\"mini\">(" + r.toPortId + ")</span></td>");
            out.println("<td>" + r.movedBikes + "</td>");
            String kind = (r.source == null || r.source.isEmpty()) ? "admin" : r.source;
            out.println("<td>" + esc(kind) + "</td>");

            // 削除機能は廃止

            out.println("</tr>");
        }

        out.println("</table></div>");
        out.println("<p class=\"mini\" style=\"margin-top:12px;\">※ 配車ログは監査のため削除できません</p>");

        out.println("</div></div></body></html>");
    }

    // POST: 削除不可（以前のDELETEデモは廃止）
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // ===== セッション認証チェック =====
        javax.servlet.http.HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("operatorId") == null) {
            response.sendRedirect(request.getContextPath() + "/adminlogin");
            return;
        }

        request.setCharacterEncoding("UTF-8");
        String q = safe(request.getParameter("q"), "").trim();
        String keep = "q=" + URLEncoder.encode(q, "UTF-8");
        response.sendRedirect(request.getContextPath() + "/portlog?" + keep + "&msg=" +
                URLEncoder.encode("配車ログは削除できません", "UTF-8"));
    }
}
