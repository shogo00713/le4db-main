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

@SuppressWarnings("serial")
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
        
        // CSS（簡易）
        out.println("<style>");
        out.println("*{margin:0;padding:0;box-sizing:border-box;}");
        out.println("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Noto Sans JP','Hiragino Sans','Hiragino Kaku Gothic ProN',Meiryo,sans-serif;background:#fff;color:#2d3748;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px;}");
        out.println(".login-container{background:rgba(255,255,255,0.98);backdrop-filter:blur(10px);border-radius:20px;box-shadow:0 20px 60px rgba(0,0,0,0.15);padding:40px;width:100%;max-width:420px;}");
        out.println(".login-title{margin:0 0 10px 0;font-size:32px;font-weight:700;text-align:center;color:#1a202c;letter-spacing:-0.5px;}");
        out.println(".login-muted{color:#718096;font-size:14px;text-align:center;margin:0 0 30px 0;}");
        out.println(".form-group{margin:20px 0;}");
        out.println("label{display:block;margin:0 0 8px 0;font-size:14px;font-weight:600;color:#4a5568;}");
        out.println("input,select{width:100%;padding:12px 16px;border:2px solid #e2e8f0;border-radius:12px;font-size:15px;transition:all 0.2s ease;background:#f7fafc;}");
        out.println("input:focus,select:focus{outline:none;border-color:#3b82f6;background:#fff;box-shadow:0 0 0 3px rgba(59,130,246,0.1);}");
        out.println(".btn{width:100%;padding:14px 20px;background:linear-gradient(135deg,#3b82f6 0%,#1d4ed8 100%);color:#fff;border:none;border-radius:12px;font-size:16px;font-weight:600;cursor:pointer;margin-top:28px;transition:all 0.3s ease;box-shadow:0 4px 15px rgba(59,130,246,0.4);}");
        out.println(".btn:hover{transform:translateY(-2px);box-shadow:0 6px 20px rgba(255,255,255,0.98);}");
        out.println(".btn:active{transform:translateY(0);}");
        out.println(".alert{padding:14px 16px;border-radius:12px;background:#fed7d7;border:1px solid #fc8181;color:#c53030;margin:20px 0;font-size:14px;}");
        out.println("a{color:#3b82f6;text-decoration:none;transition:color 0.2s ease;}");
        out.println("a:hover{color:#1d4ed8;}");
        out.println("</style>");

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
            String sql = "SELECT DISTINCT operator_id, operator_name FROM port_status ORDER BY operator_name";
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

        out.println("<div style=\"text-align:center;margin-top:20px;\">");
        out.println("<a href=\"" + request.getContextPath() + "/routesearch\" style=\"color:#007AFF;text-decoration:none;font-size:14px;\">← ルート検索に戻る</a>");
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

        // パスワードをデータベースから取得（admin_credentials テーブル）
        String storedPassword = null;
        String operatorName = null;

        try (Connection conn = DatabaseConfig.getConnection()) {
            String sql = "SELECT password, operator_name FROM admin_credentials WHERE operator_id = ?";
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
