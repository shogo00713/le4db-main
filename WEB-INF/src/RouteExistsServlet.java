import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class RouteExistsServlet extends HttpServlet {

  private String _hostname = null;
  private String _dbname = null;
  private String _username = null;
  private String _password = null;

  public void init() throws ServletException {
    String iniFilePath = getServletConfig().getServletContext()
        .getRealPath("WEB-INF/le4db.ini");
    try {
      FileInputStream fis = new FileInputStream(iniFilePath);
      Properties prop = new Properties();
      prop.load(fis);
      _hostname = prop.getProperty("hostname");
      _dbname   = prop.getProperty("dbname");
      _username = prop.getProperty("username");
      _password = prop.getProperty("password");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    response.setContentType("text/html;charset=UTF-8");
    PrintWriter out = response.getWriter();

    String from = request.getParameter("from_stop");
    String to   = request.getParameter("to_stop");
    String day  = request.getParameter("day"); // 平日/休日/空

    out.println("<html><head><meta charset=\"UTF-8\"></head><body>");
    out.println("<h2>ルートがあるかチェック（同一便）</h2>");

    out.println("<form action=\"/routeexists\" method=\"GET\">");
    out.println("出発（部分一致）: <input type=\"text\" name=\"from_stop\" value=\"" + esc(from) + "\"/><br/>");
    out.println("到着（部分一致）: <input type=\"text\" name=\"to_stop\" value=\"" + esc(to) + "\"/><br/>");
    out.println("運行日: <select name=\"day\">");
    out.println(option("", "（すべて）", day));
    out.println(option("平日", "平日", day));
    out.println(option("休日", "休日", day));
    out.println("</select><br/>");
    out.println("<input type=\"submit\" value=\"調べる\"/>");
    out.println("</form><hr/>");

    if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
      out.println("<p>出発・到着を入れてください。</p>");
      out.println("</body></html>");
      return;
    }

    Connection conn = null;
    PreparedStatement ps = null;
    ResultSet rs = null;

    try {
      Class.forName("org.postgresql.Driver");
      conn = DriverManager.getConnection(
          "jdbc:postgresql://" + _hostname + ":5432/" + _dbname,
          _username, _password
      );

      String sql =
        "SELECT DISTINCT " +
        "  r.route_name, t.trip_name, t.trip_datetime, " +
        "  sf.stop_name AS from_stop, st.stop_name AS to_stop, " +
        "  sa_from.arrival_order AS from_order, sa_to.arrival_order AS to_order " +
        "FROM stop_at sa_from " +
        "JOIN stop_information sf ON sa_from.stop_id = sf.stop_id " +
        "JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id " +
        "JOIN stop_information st ON sa_to.stop_id = st.stop_id " +
        "JOIN trip_information t ON t.trip_id = sa_from.trip_id " +
        "LEFT JOIN route_trip rt ON rt.trip_id = t.trip_id " +
        "LEFT JOIN route_information r ON r.route_id = rt.route_id " +
        "WHERE sf.stop_name ILIKE ? " +
        "  AND st.stop_name ILIKE ? " +
        "  AND sa_from.arrival_order < sa_to.arrival_order " +
        "  AND ( ? = '' OR t.trip_datetime = ? ) " +
        "ORDER BY r.route_name NULLS LAST, t.trip_name " +
        "LIMIT 40";

      ps = conn.prepareStatement(sql);
      ps.setString(1, "%" + from + "%");
      ps.setString(2, "%" + to + "%");
      ps.setString(3, day == null ? "" : day);
      ps.setString(4, day == null ? "" : day);

      rs = ps.executeQuery();

      boolean any = false;
      out.println("<h3>結果</h3>");
      out.println("<table border=\"1\" cellpadding=\"6\">");
      out.println("<tr><th>路線</th><th>便</th><th>運行日</th><th>出発</th><th>到着</th><th>順序</th></tr>");

      while (rs.next()) {
        any = true;
        out.println("<tr>");
        out.println("<td>" + esc(nvl(rs.getString("route_name"))) + "</td>");
        out.println("<td>" + esc(rs.getString("trip_name")) + "</td>");
        out.println("<td>" + esc(rs.getString("trip_datetime")) + "</td>");
        out.println("<td>" + esc(rs.getString("from_stop")) + "</td>");
        out.println("<td>" + esc(rs.getString("to_stop")) + "</td>");
        out.println("<td>" + rs.getInt("from_order") + " → " + rs.getInt("to_order") + "</td>");
        out.println("</tr>");
      }
      out.println("</table>");

      out.println(any ? "<p><b>結論：あります</b></p>" : "<p><b>結論：見つかりません</b></p>");

    } catch (Exception e) {
      out.println("<pre>エラー: " + e + "</pre>");
      e.printStackTrace();
    } finally {
      try { if (rs != null) rs.close(); } catch (SQLException e) {}
      try { if (ps != null) ps.close(); } catch (SQLException e) {}
      try { if (conn != null) conn.close(); } catch (SQLException e) {}
    }

    out.println("</body></html>");
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    doGet(request, response);
  }

  private String nvl(String s) { return s == null ? "" : s; }

  private String esc(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
  }

  private String option(String value, String label, String current) {
    String selected = (current != null && current.equals(value)) ? " selected" : "";
    return "<option value=\"" + esc(value) + "\"" + selected + ">" + esc(label) + "</option>";
  }
}
