import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@SuppressWarnings("serial")
public class AdminLoginServlet extends HttpServlet {

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

    private Connection openConn() throws Exception {
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
        out.println("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Noto Sans JP','Hiragino Kaku Gothic ProN',Meiryo,sans-serif;margin:0;background:#f5f5f7;color:#1d1d1f;display:flex;justify-content:center;align-items:center;min-height:100vh;}");
        out.println(".login-container{background:#fff;border:1px solid rgba(0,0,0,.10);border-radius:16px;box-shadow:0 10px 28px rgba(0,0,0,.08);padding:32px;width:100%;max-width:400px;}");
        out.println(".login-title{margin:0 0 8px 0;font-size:28px;text-align:center;}");
        out.println(".login-muted{color:#6e6e73;font-size:13px;text-align:center;margin:0 0 24px 0;}");
        out.println(".form-group{margin:16px 0;}");
        out.println("label{display:block;margin:8px 0 4px 0;font-size:14px;font-weight:500;}");
        out.println("input,select{width:100%;padding:10px 12px;border:1px solid rgba(0,0,0,.12);border-radius:8px;font-size:14px;box-sizing:border-box;}");
        out.println("input:focus,select:focus{outline:none;border-color:#007AFF;box-shadow:0 0 0 3px rgba(0,122,255,.1);}");
        out.println(".btn{width:100%;padding:12px 16px;background:#007AFF;color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:500;cursor:pointer;margin-top:24px;}");
        out.println(".btn:hover{background:#0056b3;}");
        out.println(".alert{padding:12px;border-radius:8px;background:#fee;border:1px solid #f99;color:#c33;margin:16px 0;font-size:13px;}");
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
        try (Connection conn = openConn()) {
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

        try (Connection conn = openConn()) {
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
