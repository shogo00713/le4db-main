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

        LogRow(int logId, String movedAt, int operatorId, String operatorName,
               int fromPortId, String fromName, int toPortId, String toName, int movedBikes) {
            this.logId = logId;
            this.movedAt = movedAt;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.fromPortId = fromPortId;
            this.fromName = fromName;
            this.toPortId = toPortId;
            this.toName = toName;
            this.movedBikes = movedBikes;
        }
    }

    // GET: ログ一覧 + 検索 + 集約
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String q = safe(request.getParameter("q"), "").trim();           // port名検索
        String opStr = safe(request.getParameter("op"), "");             // operator_id フィルタ（任意）
        String msg = safe(request.getParameter("msg"), "");

        int opId = -1;
        if (opStr.matches("\\d+")) opId = Integer.parseInt(opStr);

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
            "  l.moved_bikes " +
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
        // operatorごとの移動回数＆合計台数
        class Agg { int operatorId; String operatorName; int cnt; int sum; }
        List<Agg> aggs = new ArrayList<>();

        String aggSql =
            "SELECT l.operator_id, COALESCE(op.operator_name,'(unknown)') AS operator_name, " +
            "       COUNT(*) AS cnt, SUM(l.moved_bikes) AS sum_bikes " +
            "FROM bike_move_log l " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM port_status) op " +
            "  ON op.operator_id = l.operator_id " +
            "GROUP BY l.operator_id, op.operator_name " +
            "ORDER BY sum_bikes DESC, cnt DESC " +
            "LIMIT 10";

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
                            rs.getInt("moved_bikes")
                        ));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(aggSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Agg a = new Agg();
                    a.operatorId = rs.getInt("operator_id");
                    a.operatorName = rs.getString("operator_name");
                    a.cnt = rs.getInt("cnt");
                    a.sum = rs.getInt("sum_bikes");
                    aggs.add(a);
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
        out.println("<p class=\"muted\">検索(SELECT+JOIN) / 削除(DELETE) / 集約(GROUP BY) をここでデモできます</p>");
        out.println("</div>");
        out.println("<div class=\"row\">");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portadmin/\">ポート管理へ戻る</a>");
        out.println("</div>");
        out.println("</div>");

        if (!msg.isEmpty()) out.println("<div class=\"alert\">" + esc(msg) + "</div>");

        // 検索フォーム
        out.println("<form method=\"GET\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");
        out.println("<input type=\"text\" name=\"q\" placeholder=\"ポート名で検索（例: 京都駅）\" value=\"" + esc(q) + "\"/>");
        out.println("<input type=\"number\" name=\"op\" placeholder=\"operator_id（任意）\" value=\"" + (opId == -1 ? "" : opId) + "\" style=\"width:180px;\"/>");
        out.println("<button class=\"btn\" type=\"submit\">検索</button>");
        out.println("</div>");
        out.println("</form>");

        // 集約結果（上位だけ）
        out.println("<div class=\"row\">");
        out.println("<div class=\"alert\" style=\"background:#fff7ed;border-color:rgba(245,158,11,.28);color:#92400e;\">");
        out.println("<b>集約（operator別）</b>：移動回数と合計台数（COUNT / SUM）<br/>");
        for (int i = 0; i < aggs.size(); i++) {
            Agg a = aggs.get(i);
            out.println("<span class=\"mini\">#" + (i+1) + " </span>"
                    + esc(a.operatorName) + "（" + a.operatorId + "）: "
                    + a.cnt + "回 / 合計 " + a.sum + "台<br/>");
        }
        out.println("</div>");
        out.println("</div>");

        // ログ表
        out.println("<div class=\"table-wrap\"><table>");
        out.println("<tr>");
        out.println("<th>log_id</th><th>日時</th><th>operator</th><th>from</th><th>to</th><th>台数</th><th>削除</th>");
        out.println("</tr>");

        for (LogRow r : rows) {
            out.println("<tr>");
            out.println("<td>" + r.logId + "</td>");
            out.println("<td>" + esc(r.movedAt) + "</td>");
            out.println("<td>" + esc(r.operatorName) + " <span class=\"mini\">(#" + r.operatorId + ")</span></td>");
            out.println("<td>" + esc(r.fromName) + " <span class=\"mini\">(" + r.fromPortId + ")</span></td>");
            out.println("<td>" + esc(r.toName) + " <span class=\"mini\">(" + r.toPortId + ")</span></td>");
            out.println("<td>" + r.movedBikes + "</td>");

            out.println("<td>");
            out.println("<form method=\"POST\" action=\"" + basePath + "\" onsubmit=\"return confirm('このログを削除しますか？');\">");
            out.println("<input type=\"hidden\" name=\"action\" value=\"delete\"/>");
            out.println("<input type=\"hidden\" name=\"log_id\" value=\"" + r.logId + "\"/>");
            out.println("<input type=\"hidden\" name=\"q\" value=\"" + esc(q) + "\"/>");
            out.println("<input type=\"hidden\" name=\"op\" value=\"" + (opId == -1 ? "" : opId) + "\"/>");
            out.println("<button class=\"btn2\" type=\"submit\">削除</button>");
            out.println("</form>");
            out.println("</td>");

            out.println("</tr>");
        }

        out.println("</table></div>");
        out.println("<p class=\"mini\" style=\"margin-top:12px;\">※ DELETE のデモ：ログ削除ボタン（bike_move_log から削除）</p>");

        out.println("</div></div></body></html>");
    }

    // POST: ログ削除（DELETE）
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String action = safe(request.getParameter("action"), "");
        if (!"delete".equals(action)) {
            response.sendRedirect(request.getContextPath() + "/portlog");
            return;
        }

        String q = safe(request.getParameter("q"), "").trim();
        String op = safe(request.getParameter("op"), "").trim();

        String keep = "q=" + URLEncoder.encode(q, "UTF-8")
                    + "&op=" + URLEncoder.encode(op, "UTF-8");

        String idStr = request.getParameter("log_id");
        int logId;
        try {
            logId = Integer.parseInt(idStr);
        } catch (Exception e) {
            response.sendRedirect(request.getContextPath() + "/portlog?" + keep + "&msg=" +
                    URLEncoder.encode("log_id が不正です", "UTF-8"));
            return;
        }

        String delSql = "DELETE FROM bike_move_log WHERE log_id = ?";

        try (Connection conn = openConn();
             PreparedStatement ps = conn.prepareStatement(delSql)) {

            ps.setInt(1, logId);
            int n = ps.executeUpdate();

            if (n == 0) {
                response.sendRedirect(request.getContextPath() + "/portlog?" + keep + "&msg=" +
                        URLEncoder.encode("削除できませんでした（log_idが見つからない）", "UTF-8"));
            } else {
                response.sendRedirect(request.getContextPath() + "/portlog?" + keep + "&msg=" +
                        URLEncoder.encode("削除しました（log_id=" + logId + "）", "UTF-8"));
            }

        } catch (Exception e) {
            response.sendRedirect(request.getContextPath() + "/portlog?" + keep + "&msg=" +
                    URLEncoder.encode("DBエラー(DELETE): " + e.getMessage(), "UTF-8"));
        }
    }
}
