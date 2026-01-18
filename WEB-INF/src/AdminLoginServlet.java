import static util.HtmlUtils.esc;
import static util.HtmlUtils.safe;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class AdminLoginServlet extends HttpServlet {


    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }

    Connection conn = null; // 認証 & 接続用


    // GET: ログインフォームを表示
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("operatorId") != null) {
            // すでにログイン済みの場合は portadmin にリダイレクト
            response.sendRedirect(request.getContextPath() + "/portadmin/");
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        String msg = safe(request.getParameter("msg"), "");

        out.println("<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\"/>");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        out.println("<title>ShareCycle Admin Login</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + request.getContextPath() + "/static/app.css\"/>");
        out.println("</head><body class=\"page-admin-login\">");

        out.println("<div class=\"login-container\">");
        out.println("<h1 class=\"login-title\">管理者ログイン</h1>");
        out.println("<p class=\"login-muted\">事業者を選択してパスワードを入力</p>");

        if (!msg.isEmpty()) {
            out.println("<div class=\"alert\">" + esc(msg) + "</div>");
        }

        out.println("<form method=\"POST\" action=\"" + request.getContextPath() + "/adminlogin\">");

        out.println("<div class=\"form-group\">");
        out.println("<label for=\"operator_id\">事業者を選択</label>");
        out.println("<select name=\"operator_id\" id=\"operator_id\" required>");
        out.println("<option value=\"\">-- 選択してください --</option>");

        // operator 一覧をデータベースから取得
        try (Connection conn = DatabaseConfig.getConnection()) {
            String sql = "SELECT DISTINCT operator_id, operator_name FROM v_port_status ORDER BY operator_name";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    int opId = rs.getInt("operator_id");
                    String opName = rs.getString("operator_name");
                    out.println("<option value=\"" + opId + "\">" + esc(opName) + "</option>");
                }
            }
        } catch (Exception e) {
            out.println("<option value=\"\">エラー: 事業者一覧を取得できませんでした</option>");
        }

        out.println("</select>");
        out.println("</div>");

        out.println("<div class=\"form-group\">");
        out.println("<label for=\"password\">パスワード</label>");
        out.println("<input type=\"password\" name=\"password\" id=\"password\" required placeholder=\"パスワードを入力\"/>");
        out.println("</div>");

        out.println("<button type=\"submit\" class=\"btn\">ログイン</button>");

        out.println("</form>");

        out.println("<div class=\"link-area\">");
        out.println("<a class=\"back-link\" href=\"" + request.getContextPath() + "/routesearch\">← ルート検索に戻る</a>");
        out.println("</div>");

        out.println("</div>");
        out.println("</body></html>");
    }

    // POST: パスワード認証
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");

        String operatorIdStr = safe(request.getParameter("operator_id"), "").trim();
        String password = safe(request.getParameter("password"), "");

        int operatorId;
        try {
            operatorId = Integer.parseInt(operatorIdStr);
        } catch (Exception e) {
            response.sendRedirect(request.getContextPath() + "/adminlogin?msg=" +
                    URLEncoder.encode("事業者を選択してください", "UTF-8"));
            return;
        }

        // パスワードをデータベースから取得（v_operator_accounts ビュー）
        String storedPassword = null;
        String operatorName = null;

        try (Connection conn = DatabaseConfig.getConnection()) {
            String sql = "SELECT password, operator_name FROM v_operator_accounts WHERE operator_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, operatorId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        storedPassword = rs.getString("password");
                        operatorName = rs.getString("operator_name");
                    }
                }
            }
        } catch (Exception e) {
            response.sendRedirect(request.getContextPath() + "/adminlogin?msg=" +
                    URLEncoder.encode("DBエラー: " + e.getMessage(), "UTF-8"));
            return;
        }

        // パスワード検証
        if (storedPassword == null || !storedPassword.equals(password)) {
            response.sendRedirect(request.getContextPath() + "/adminlogin?msg=" +
                    URLEncoder.encode("パスワードが正しくありません", "UTF-8"));
            return;
        }

        // セッションを作成してログイン状態を保存
        HttpSession session = request.getSession(true);
        session.setAttribute("operatorId", operatorId);
        session.setAttribute("operatorName", operatorName);
        session.setMaxInactiveInterval(3600); // 1時間

        // ポート管理画面へリダイレクト
        response.sendRedirect(request.getContextPath() + "/portadmin/");
    }
}
