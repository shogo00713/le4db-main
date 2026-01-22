import static util.HtmlUtils.esc;
import static util.HtmlUtils.safe;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BikeMoveLogServlet extends HttpServlet {

    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }  

    Connection conn = null; // 認証 & 接続用

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
                "SELECT log_id, to_char(moved_at,'YYYY-MM-DD HH24:MI:SS') AS moved_at, "
              + "   operator_id, operator_name, "
              + "   from_port_id, from_port_name AS from_name, "
              + "   to_port_id,   to_port_name   AS to_name, "
              + "    moved_bikes, source "
              + "FROM v_bike_move "
              + "WHERE (? = -1 OR operator_id = ?) "
              + "AND (? = '' OR from_port_name ILIKE ? OR to_port_name ILIKE ?) "
              + "ORDER BY moved_at DESC "
              + "LIMIT 80 ";

        // ---- ユーザー利用のみの分析（source='user'）----
        class PortStat { int portId; String portName; String operatorName; int trips; }
        List<PortStat> topDepartures = new ArrayList<>();
        List<PortStat> topReturns = new ArrayList<>();
        List<PortStat> leastUsed = new ArrayList<>();

        String topDepartSql =
            "SELECT from_port_id AS port_id, from_port_name AS port_name, " +
            "       operator_id, operator_name, " +
            "       COUNT(*) AS trips " +
            "FROM v_bike_move " +
            "WHERE source = 'user' AND (? = -1 OR operator_id = ?) " +
            "GROUP BY from_port_id, from_port_name, operator_id, operator_name " +
            "ORDER BY trips DESC " +
            "LIMIT 3";

        String topReturnSql =
            "SELECT to_port_id AS port_id, to_port_name AS port_name, " +
            "       operator_id, operator_name, " +
            "       COUNT(*) AS trips " +
            "FROM v_bike_move " +
            "WHERE source = 'user' AND (? = -1 OR operator_id = ?) " +
            "GROUP BY to_port_id, to_port_name, operator_id, operator_name " +
            "ORDER BY trips DESC " +
            "LIMIT 3";

        String leastUsedSql =
            "WITH usage AS ( " +
            "  SELECT from_port_id AS port_id, operator_id, moved_bikes FROM v_bike_move WHERE source='user' " +
            "  UNION ALL " +
            "  SELECT to_port_id   AS port_id, operator_id, moved_bikes FROM v_bike_move WHERE source='user' " +
            ") " +
            "SELECT po.port_id, COALESCE(pi.port_name,'(unknown)') AS port_name, po.operator_id, " +
            "       COALESCE(op.operator_name,'(unknown)') AS operator_name, COALESCE(u.total_bikes,0) AS total_bikes, " +
            "       COALESCE(u.trips,0) AS trips " +
            "FROM port_operation po " +
            "JOIN port_information pi ON pi.port_id = po.port_id " +
            "LEFT JOIN ( " +
            "  SELECT port_id, operator_id, SUM(moved_bikes) AS total_bikes, COUNT(*) AS trips " +
            "  FROM usage GROUP BY port_id, operator_id " +
            ") u ON u.port_id = po.port_id AND u.operator_id = po.operator_id " +
            "LEFT JOIN (SELECT DISTINCT operator_id, operator_name FROM v_port_status) op ON op.operator_id = po.operator_id " +
            "WHERE (? = -1 OR po.operator_id = ?) " +
            "ORDER BY total_bikes ASC, po.port_id ASC " +
            "LIMIT 3";

        try (Connection conn = DatabaseConfig.getConnection()) {

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


            // ユーザー利用のみ: 出発上位
            try (PreparedStatement ps = conn.prepareStatement(topDepartSql)) {
                ps.setInt(1, opId);
                ps.setInt(2, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PortStat s = new PortStat();
                        s.portId = rs.getInt("port_id");
                        s.portName = rs.getString("port_name");
                        rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
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
                        rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
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
                        rs.getInt("operator_id");
                        s.operatorName = rs.getString("operator_name");
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
        out.println("<link rel=\"stylesheet\" href=\"" + ctx + "/static/app.css\"/>");
        out.println("</head><body class=\"page-port-log\"><div class=\"app\"><div class=\"card\">");

        out.println("<div class=\"row justify-between\">");
        out.println("<div>");
        out.println("<h1 class=\"title\">配車ログ（bike_move_log）</h1>");
        out.println("<p class=\"muted\">事業者: " + esc(sessionOperatorName) + "</p>");
        out.println("</div>");
        out.println("<div class=\"row\">");
        out.println("<a class=\"btn2\" href=\"" + ctx + "/portadmin/\">ポート管理へ戻る</a>");
        out.println("</div>");
        out.println("</div>");

        if (!msg.isEmpty()) out.println("<div class=\"alert\">" + esc(msg) + "</div>");

        out.println("</div>"); // card

        // 分析結果（ユーザー利用のみ）
        out.println("<div class=\"card\">");
        out.println("<h2 class=\"title\">分析（ユーザー利用のみ）</h2>");
        out.println("<p class=\"muted\">事業者の活用検討に役立つサマリ</p>");
        out.println("<hr/>");
        
        out.println("<div class=\"row gap-24\" style=\"align-items: flex-start;\">");
        
        // 出発上位
        out.println("<div style=\"flex: 1; min-width: 280px;\">");
        out.println("<h3 style=\"margin-top: 0; color: #2563eb;\">📤 出発が多いポート TOP3</h3>");
        if (topDepartures.isEmpty()) {
            out.println("<p class=\"muted\">データがありません</p>");
        } else {
            out.println("<div style=\"background: #f8fafc; padding: 12px; border-radius: 6px;\">");
            for (int i = 0; i < topDepartures.size(); i++) {
                PortStat s = topDepartures.get(i);
                out.println("<div style=\"margin-bottom: 8px; padding-bottom: 8px; " + 
                    (i < topDepartures.size() - 1 ? "border-bottom: 1px solid #e2e8f0;" : "") + "\">");
                out.println("<div style=\"font-weight: 600;\">" + esc(s.portName) + 
                    " <span class=\"mini\" style=\"color: #64748b;\">(#" + s.portId + ")</span></div>");
                out.println("<div class=\"mini\" style=\"color: #64748b;\">- " + esc(s.operatorName) + "</div>");
                out.println("<div style=\"margin-top: 4px;\"><span style=\"color: #2563eb; font-weight: 600;\">合計 " + 
                    s.trips + "回</span></div>");
                out.println("</div>");
            }
            out.println("</div>");
        }
        out.println("</div>");
        
        // 返却上位
        out.println("<div style=\"flex: 1; min-width: 280px;\">");
        out.println("<h3 style=\"margin-top: 0; color: #059669;\">📥 返却が多いポート TOP3</h3>");
        if (topReturns.isEmpty()) {
            out.println("<p class=\"muted\">データがありません</p>");
        } else {
            out.println("<div style=\"background: #f8fafc; padding: 12px; border-radius: 6px;\">");
            for (int i = 0; i < topReturns.size(); i++) {
                PortStat s = topReturns.get(i);
                out.println("<div style=\"margin-bottom: 8px; padding-bottom: 8px; " + 
                    (i < topReturns.size() - 1 ? "border-bottom: 1px solid #e2e8f0;" : "") + "\">");
                out.println("<div style=\"font-weight: 600;\">" + esc(s.portName) + 
                    " <span class=\"mini\" style=\"color: #64748b;\">(#" + s.portId + ")</span></div>");
                out.println("<div class=\"mini\" style=\"color: #64748b;\">- " + esc(s.operatorName) + "</div>");
                out.println("<div style=\"margin-top: 4px;\"><span style=\"color: #059669; font-weight: 600;\">合計 " + 
                    s.trips + "回</span></div>");
                out.println("</div>");
            }
            out.println("</div>");
        }
        out.println("</div>");
        
        // 未使用寄り
        out.println("<div style=\"flex: 1; min-width: 280px;\">");
        out.println("<h3 style=\"margin-top: 0; color: #dc2626;\">⚠️ 未使用寄りポート TOP3</h3>");
        if (leastUsed.isEmpty()) {
            out.println("<p class=\"muted\">データがありません</p>");
        } else {
            out.println("<div style=\"background: #fef2f2; padding: 12px; border-radius: 6px;\">");
            for (int i = 0; i < leastUsed.size(); i++) {
                PortStat s = leastUsed.get(i);
                out.println("<div style=\"margin-bottom: 8px; padding-bottom: 8px; " + 
                    (i < leastUsed.size() - 1 ? "border-bottom: 1px solid #fecaca;" : "") + "\">");
                out.println("<div style=\"font-weight: 600;\">" + esc(s.portName) + 
                    " <span class=\"mini\" style=\"color: #64748b;\">(#" + s.portId + ")</span></div>");
                out.println("<div class=\"mini\" style=\"color: #64748b;\">- " + esc(s.operatorName) + "</div>");
                out.println("<div style=\"margin-top: 4px;\"><span style=\"color: #dc2626; font-weight: 600;\">合計 " + 
                    s.trips + "回</span></div>");
                out.println("</div>");
            }
            out.println("</div>");
        }
        out.println("</div>");
        
        out.println("</div>"); // row
        out.println("</div>"); // card

        out.println("<div class=\"card\">");
        out.println("<h2 class=\"title\">ログ検索・一覧</h2>");

        // 検索フォーム
        out.println("<form method=\"GET\" action=\"" + basePath + "\">");
        out.println("<div class=\"row\">");
        out.println("<input type=\"text\" name=\"q\" placeholder=\"ポート名で検索（例: 京都駅）\" value=\"" + esc(q) + "\"/>");
        out.println("<button class=\"btn\" type=\"submit\">検索</button>");
        out.println("</div>");
        out.println("</form>");

        // ログ表
        out.println("<hr/>");
        out.println("<h3 style=\"margin-top: 16px;\">配車履歴（最新80件）</h3>");
        out.println("<div class=\"table-wrap\"><table>");
        out.println("<thead>");
        out.println("<tr>");
        out.println("<th style=\"width: 80px;\">log_id</th>");
        out.println("<th style=\"width: 150px;\">日時</th>");
        out.println("<th style=\"width: 150px;\">事業者</th>");
        out.println("<th>出発</th>");
        out.println("<th>到着</th>");
        out.println("<th style=\"width: 80px;\">台数</th>");
        out.println("<th style=\"width: 100px;\">種別</th>");
        out.println("</tr>");
        out.println("</thead>");
        out.println("<tbody>");

        for (LogRow r : rows) {
            String kindClass = "user".equals(r.source) ? "color: #059669;" : "color: #64748b;";
            out.println("<tr>");
            out.println("<td>" + r.logId + "</td>");
            out.println("<td style=\"font-size: 0.9em;\">" + esc(r.movedAt) + "</td>");
            out.println("<td>" + esc(r.operatorName) + " <span class=\"mini\" style=\"color: #94a3b8;\">#" + r.operatorId + "</span></td>");
            out.println("<td><b>" + esc(r.fromName) + "</b> <span class=\"mini\" style=\"color: #94a3b8;\">#" + r.fromPortId + "</span></td>");
            out.println("<td><b>" + esc(r.toName) + "</b> <span class=\"mini\" style=\"color: #94a3b8;\">#" + r.toPortId + "</span></td>");
            out.println("<td style=\"text-align: center; font-weight: 600;\">" + r.movedBikes + "</td>");
            String kind = (r.source == null || r.source.isEmpty()) ? "admin" : r.source;
            String badge = "user".equals(kind) ? 
                "<span style=\"background: #d1fae5; color: #065f46; padding: 4px 8px; border-radius: 4px; font-size: 0.85em; font-weight: 600;\">👤 ユーザー</span>" :
                "<span style=\"background: #f1f5f9; color: #475569; padding: 4px 8px; border-radius: 4px; font-size: 0.85em; font-weight: 600;\">🔧 管理</span>";
            out.println("<td style=\"text-align: center;\">" + badge + "</td>");

            out.println("</tr>");
        }

        out.println("</tbody>");
        out.println("</table></div>");
        out.println("</div></div></body></html>");
    }

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
