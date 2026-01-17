import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@SuppressWarnings("serial")

public class RouteSearchServlet extends HttpServlet {

    // サーバ接続の変数定義
    private String _hostname = null;
    private String _dbname = null;
    private String _username = null;
    private String _password = null;

    // DB初期設定の関数
    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext()
                .getRealPath("WEB-INF/le4db.ini");

        try {
            FileInputStream fis = new FileInputStream(iniFilePath);
            Properties prop = new Properties();
            prop.load(fis);
            _hostname = prop.getProperty("hostname");
            _dbname = prop.getProperty("dbname");
            _username = prop.getProperty("username");
            _password = prop.getProperty("password");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // メインの関数 (doGet)
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String view = request.getParameter("view");
        if ("detail".equals(view)) {
            renderDetailPage(request, response);
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        // フォームでやり取りするパラメータ
        String fromstop = request.getParameter("from_stop"); // 出発地
        String tostop = request.getParameter("to_stop"); // 目的地
        String day = request.getParameter("day"); // 平日 or 休日
        String timemode = request.getParameter("time_mode"); // 現在 or 指定時間
        String timevalue = request.getParameter("time_val"); // HH:mm
        String fromstopidstr = request.getParameter("from_id"); // 出発地候補ID (選択された後)
        String tostopidstr = request.getParameter("to_id"); // 目的地候補ID (選択された後)

        // ---- いじる定数 ----

        // 時間関係定数
        final int TRANSFER_MIN = 2; // 乗り換えするのに必要な最低時間
        final int BIKE_UNLOCK_MIN = 1; // 借りる / 解錠 の最低時間
        final int BIKE_LOCK_MIN = 1; // 返す / 施錠 の最低時間

        // 距離関係定数
        final int FROM_RADIUS_M = 1000; // 出発地周りの徒歩圏最大
        final int TO_RADIUS_M = 1000; // 到着地周りの徒歩圏最大
        final int TRANSFER_RADIUS_M = 300; // 乗換の徒歩圏最大
        final int BIKE_PORT_RADIUS_M = 400; // 停留所 と ポート間の徒歩圏最大
        final int BIKE_MAX_RIDE_M = 6000; // 自転車移動の最大距離（暴走防止）

        // 探索関係定数
        final int MID_LIMIT = 10; // 乗り換え地点候補の探索数上限
        final int NEAR_LIMIT = 30; // 乗換経路探索数上限
        final int PORT_LIMIT = 5; // 近隣ポートの探索数上限
        final int RESULT_LIMIT = 5; // 表示する乗換経路の最大

        // 徒歩/自転車速度関係定数
        final double METER_CORRECTION = 1.25; // 徒歩距離補正係数 (直線 -> 道のり)
        final double METER_PER_MINUTE = 80.0; // 徒歩の速さは 80m/分
        final double BIKE_METER_CORRECTION = 1.5; // 自転車距離補正係数 (直線 -> 自転車通行可能な道のり)
        final double BIKE_METER_PER_MINUTE = 250.0; // 自転車の速さは 250m/分

        // -------------------

        // -----------------------------------------------------------------------------------------------

        Integer fromid = null; // Integer 型で null 許容
        Integer toid = null; // Integer 型で null 許容

        // fromidStr/toidStr (String型) を fromid/toid (Integer型) に変換
        if (fromstopidstr != null && !fromstopidstr.trim().isEmpty()) {
            try {
                fromid = Integer.valueOf(fromstopidstr);
            } catch (NumberFormatException e) {
                fromid = null;
            }
        }
        if (tostopidstr != null && !tostopidstr.trim().isEmpty()) {
            try {
                toid = Integer.valueOf(tostopidstr);
            } catch (NumberFormatException e) {
                toid = null;
            }
        }

        // NULL => 空文字列 に変換 (エラー対策)
        if (fromstop == null)
            fromstop = "";
        if (tostop == null)
            tostop = "";
        if (day == null || day.isEmpty())
            day = "平日";
        if (timemode == null)
            timemode = "now";
        if (timevalue == null)
            timevalue = "";

        // -----------------------------------------------------------------------------------------------

        // HTMLヘッダ部分
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"ja\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\">");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        out.println("<title>RouteSearch</title>");

        // CSS 直書き (外部ファイルに変更予定)
        out.println("<style>");
        out.println(":root{"
                + "--bg:#f3f6fa;"
                + "--panel:#fff;"
                + "--panelSolid:#fff;"
                + "--text:#222;"
                + "--muted:#7a869a;"
                + "--border:#e3e8ee;"
                + "--hairline:#e3e8ee;"
                + "--shadow:0 8px 32px rgba(60,80,120,.10);"
                + "--shadow2:0 2px 8px rgba(60,80,120,.08);"
                + "--radius:18px;"
                + "--gap:18px;"
                + "--primary:#3b82f6;"
                + "--primary2:#2563eb;"
                + "--ring:rgba(59,130,246,.18);"
                + "--accent:#fbbf24;"
                + "}");
        out.println("*{box-sizing:border-box;}");
        out.println("html,body{height:100%;}");
        out.println("body{margin:0;background:var(--bg);color:var(--text);font-family:'Segoe UI',Roboto,'Noto Sans JP',Meiryo,sans-serif;-webkit-font-smoothing:antialiased;moz-osx-font-smoothing:grayscale;}");
        out.println(".app{max-width:1150px;margin:32px auto;padding:0 18px;}");
        out.println(".header{margin-bottom:18px;display:flex;align-items:flex-end;justify-content:space-between;gap:18px;}");
        out.println(".title{font-size:26px;line-height:1.2;margin:0;letter-spacing:-.01em;font-weight:700;}");
        out.println(".subtitle{margin:0;color:var(--muted);font-size:15px;}");
        out.println(".card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);box-shadow:var(--shadow);padding:22px 24px 18px 24px;backdrop-filter: blur(10px);}");
        out.println(".form{display:grid;grid-template-columns:1fr 1fr;gap:var(--gap);align-items:end;}");
        out.println("@media (max-width: 820px){.form{grid-template-columns:1fr;}}");
        out.println(".field{display:flex;flex-direction:column;gap:8px;}");
        out.println(".label{font-size:13px;color:var(--muted);font-weight:500;}");
        out.println(".input,.select{width:100%;padding:13px 14px;border:1px solid var(--hairline);border-radius:14px;background:rgba(255,255,255,.98);font-size:15px;outline:none;transition:border-color .15s ease, box-shadow .15s ease;}");
        out.println(".input:focus,.select:focus{border-color:var(--primary);box-shadow:0 0 0 5px var(--ring);}");
        out.println(".input::placeholder{color:rgba(110,110,115,.85);}");
        out.println(".actions{grid-column:1/-1;display:flex;flex-wrap:wrap;gap:12px;align-items:center;}");
        out.println(".btn{appearance:none;border:none;border-radius:999px;padding:12px 24px;font-weight:700;background:linear-gradient(90deg, var(--primary), var(--primary2));color:#fff;cursor:pointer;box-shadow:0 6px 16px rgba(59,130,246,.12);transition:transform .12s, box-shadow .12s, filter .12s;}");
        out.println(".btn:hover{filter:saturate(1.08);box-shadow:0 10px 24px rgba(59,130,246,.18);}");
        out.println(".btn:active{transform:translateY(1px);box-shadow:0 4px 12px rgba(59,130,246,.12);}");
        out.println(".hr{height:1px;background:var(--border);margin:18px 0;}");
        out.println(".alert{padding:12px 16px;border-radius:16px;background:#fffbe6;border:1px solid var(--accent);color:#92400e;font-size:15px;}");
        out.println(".fixed{padding:12px 16px;border-radius:14px;background:rgba(0,0,0,.03);border:1px solid var(--border);font-size:15px;}");
        out.println(".muted{color:var(--muted);font-size:14px;}");
        out.println(".result-title{font-size:18px;margin:0 0 10px 0;letter-spacing:-.01em;font-weight:600;}");
        out.println(".route-banner{font-size:22px;font-weight:900;margin:10px 0 14px 0;letter-spacing:-.01em;color:var(--primary2);}");
        out.println(".route-banner .arrow{color:var(--muted);padding:0 10px;}");
        out.println(".table-wrap{overflow:auto;border:1px solid var(--border);border-radius:16px;background:var(--panelSolid);box-shadow:var(--shadow2);margin-bottom:18px;}");
        out.println("table{width:100%;border-collapse:separate;border-spacing:0;min-width:720px;}");
        out.println("th,td{padding:13px 14px;border-bottom:1px solid var(--hairline);text-align:left;font-size:15px;white-space:nowrap;}");
        out.println("th{background:rgba(250,250,252,.98);font-size:13px;color:#3a3a3c;position:sticky;top:0;z-index:2;}");
        out.println("tr:hover td{background:rgba(59,130,246,.04);}");
        out.println(".detail-row td{background:rgba(59,130,246,.02);}");
        out.println(".steps{display:flex;flex-direction:column;gap:14px;margin-top:14px;}");
        out.println(".step{display:grid;grid-template-columns: 60px 1fr auto;gap:14px;padding:14px 14px;border:1px solid var(--border);border-radius:16px;background:#fff;box-shadow:0 2px 8px rgba(60,80,120,.08);}");
        out.println(".step .kind{font-weight:800;font-size:13px;letter-spacing:.04em;align-self:center;padding:7px 10px;border-radius:999px;background:rgba(59,130,246,.10);color:var(--primary2);text-align:center;}");
        out.println(".step .main{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:15px;}");
        out.println(".step .meta{white-space:nowrap;color:var(--muted);font-size:13px;}");
        out.println(".detail-card{max-width:700px;margin:32px auto;padding:28px 32px;background:var(--panel);border-radius:20px;box-shadow:var(--shadow);border:1px solid var(--border);}");
        out.println(".detail-header{font-size:22px;font-weight:700;margin-bottom:10px;color:var(--primary2);}");
        out.println(".detail-summary{font-size:16px;color:var(--muted);margin-bottom:18px;}");
        out.println(".back-btn{display:inline-block;margin-bottom:18px;padding:10px 22px;background:linear-gradient(90deg, var(--primary), var(--primary2));color:#fff;border-radius:999px;font-weight:700;text-decoration:none;box-shadow:0 4px 12px rgba(59,130,246,.10);transition:filter .12s, box-shadow .12s;}");
        out.println(".back-btn:hover{filter:saturate(1.08);box-shadow:0 8px 24px rgba(59,130,246,.18);}");
        out.println(".back-btn:active{filter:brightness(.98);}");
        out.println(".steps{display:flex;flex-direction:column;gap:14px;margin-top:14px;}");
        out.println(".step{display:grid;grid-template-columns: 60px 1fr auto;gap:14px;padding:14px 14px;border:1px solid var(--border);border-radius:16px;background:#fff;box-shadow:0 2px 8px rgba(60,80,120,.08);}");
        out.println(".step .kind{font-weight:800;font-size:13px;letter-spacing:.04em;align-self:center;padding:7px 10px;border-radius:999px;background:rgba(59,130,246,.10);color:var(--primary2);text-align:center;}");
        out.println(".step .main{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:15px;}");
        out.println(".step .meta{white-space:nowrap;color:var(--muted);font-size:13px;}");
        out.println(".path{display:flex;flex-wrap:wrap;align-items:center;gap:8px;}");
        out.println(".tag{display:inline-flex;align-items:center;gap:8px;padding:6px 10px;border-radius:999px;border:1px solid var(--border);background:#fff;font-size:14px;font-weight:800;}");
        out.println(".tag.line{--line:#9ca3af;}");
        out.println(".tag.line:before{content:\"\";width:10px;height:10px;border-radius:3px;background:var(--line);}");
        out.println(".tag.walk{background:rgba(0,0,0,.03);color:#374151;font-weight:500;}");
        out.println(".tag.transfer{background:rgba(251,191,36,.18);border-color:rgba(251,191,36,.55);color:#92400e;font-weight:800;}");
        out.println(".tag.bike{background:rgba(34,197,94,.12);border-color:rgba(34,197,94,.35);color:#166534;font-weight:600;}");
        out.println(".arrow-mini{color:var(--muted);font-weight:900;}");
        out.println("</style>");
        // CSS ここまで

        // HTML本文
        out.println("</head>");
        out.println("<body>");
        out.println("<div class=\"app\">");

        out.println("<div class=\"header\">");
        out.println("<div>");
        out.println("<h2 class=\"title\">マルチモーダル路線検索</h2>");
        out.println("<p class=\"subtitle\">出発地/到着地, 運行日, 時刻条件を指定して検索</p>");
        out.println("</div>");

        String ctx = request.getContextPath();
        out.println("<a class=\"adminbtn\" href=\"" + ctx + "/portadmin/\">管理者</a>");
        out.println("</div>");

        out.println("<div class=\"card\">");

        // フォーム形式
        out.println("<form class=\"form\" action=\"" + request.getContextPath() + "/routesearch\" method=\"GET\">");

        // 出発地 / 目的地 => from_stop / to_stop
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"from_stop\">出発</label>");
        out.println(
                "<input class=\"input\" id=\"from_stop\" type=\"text\" name=\"from_stop\" placeholder=\"例 : 京都駅\" value=\""
                        + esc(fromstop) + "\"/>");
        out.println("</div>");

        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"to_stop\">到着</label>");
        out.println(
                "<input class=\"input\" id=\"to_stop\" type=\"text\" name=\"to_stop\" placeholder=\"例 : 三条駅\" value=\""
                        + esc(tostop) + "\"/>");
        out.println("</div>");

        // 運行日 => day
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"day\">運行日</label>");
        out.println("<select class=\"select\" id=\"day\" name=\"day\">");
        out.println(option("平日", "平日", day));
        out.println(option("休日", "休日", day));
        out.println("</select>");
        out.println("</div>");

        // 時刻指定選択 => time_mode
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"time_mode\">時刻条件</label>");
        out.println("<select class=\"select\" id=\"time_mode\" name=\"time_mode\">");
        out.println(option("now", "現在時刻", timemode));
        out.println(option("spec", "指定時刻", timemode));
        out.println("</select>");
        out.println("</div>");

        // 時刻選択 time_val
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"time_val\">指定時刻（時刻条件=指定時刻のとき）</label>");
        out.println("<input class=\"input\" id=\"time_val\" type=\"time\" name=\"time_val\" value=\"" + esc(timevalue)
                + "\"/>");
        out.println("</div>");

        // 検索ボタン
        out.println("<div class=\"actions\">");
        out.println("<input class=\"btn\" type=\"submit\" value=\"検索\"/>");
        out.println("<span class=\"muted\">※ 指定時刻以降に出発する便を検索</span>");
        out.println("</div>");

        out.println("</form>");
        out.println("<div class=\"hr\"></div>");

        // 入力が揃っているかチェック
        if (fromstop.equals("") || tostop.equals("")) {
            out.println("<p>出発と到着を入力して検索してください</p>");
            out.println("</body>");
            out.println("</html>");
            return;
        }
        if (timemode.equals("spec") && timevalue.equals("")) {
            out.println("<p>指定時刻を入力してください</p>");
            out.println("</body></html>");
            return;
        }

        // 時間を basetime として統合
        String baseTime;
        if (timemode.equals("spec") && !timevalue.equals("")) {
            baseTime = timevalue;
        } else {
            baseTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        // -----------------------------------------------------------------------------------------------

        // DB検索パート
        Connection conn = null; // 認証 & 接続用
        PreparedStatement ps = null; // DBに送る文章
        ResultSet rs = null; // DBの結果を受け取る文章
        HttpSession session = request.getSession();

        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://" + _hostname + ":5432/" + _dbname,
                    _username, _password);

            // -----------------------------------------------------------------------------------------------

            // 地点候補 => 1つに選定
            List<StopSearchCandidate> fromCandidates = new ArrayList<>();
            List<StopSearchCandidate> toCandidates = new ArrayList<>();

            // 決まっていないなら候補を探索 => 件数に応じて次の探索へ
            if (fromid == null) {
                fromCandidates = searchStopCandidates(conn, fromstop, 10);
            }
            if (toid == null) {
                toCandidates = searchStopCandidates(conn, tostop, 10);
            }

            // 0件なら終了
            if ((fromid == null && fromCandidates.isEmpty()) || (toid == null && toCandidates.isEmpty())) {
                out.println("<p class=\"alert\">出発/到着地点が見つかりませんでした。</p>");
                out.println("</div></div></body></html>"); // card/app/body/html を閉じる
                return;
            }

            // 1件なら自動確定
            if (fromid == null && fromCandidates.size() == 1) {
                fromid = fromCandidates.get(0).stopId;
            }
            if (toid == null && toCandidates.size() == 1) {
                toid = toCandidates.get(0).stopId;
            }

            // 複数件 (少なくともどちらかが) なら、候補を選ばせる画面を出す
            if (fromid == null || toid == null) {
                out.println("<div class=\"alert\">候補が複数あります。下から選んでください。</div>");
                out.println("<form class=\"form\" action=\"routesearch\" method=\"GET\">");

                // 元の入力値も引き継ぐ（これがないと条件が消える）
                out.println("<input type=\"hidden\" name=\"from_stop\" value=\"" + esc(fromstop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"to_stop\" value=\"" + esc(tostop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"day\" value=\"" + esc(day) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_mode\" value=\"" + esc(timemode) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");

                // 出発地
                if (fromid != null) {
                    out.println("<input type=\"hidden\" name=\"from_id\" value=\"" + fromid + "\"/>");

                    Stop fixedoriginStop = getStopById(conn, fromid);
                    if (fixedoriginStop != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">出発 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedoriginStop.name) + " ("
                                + esc(fixedoriginStop.type) + ")</div>");
                        out.println("</div>");
                    }
                } else {
                    // 選択画面
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"from_id\">出発 (候補)</label>");
                    out.println("<select class=\"select\" id=\"from_id\" name=\"from_id\">");
                    for (StopSearchCandidate c : fromCandidates) {
                        out.println("<option value=\"" + c.stopId + "\">" + esc(c.stopName) + " (" + esc(c.stopType)
                                + ")</option>");
                    }
                    out.println("</select>");
                    out.println("</div>");
                }

                // 到着地
                if (toid != null) {
                    out.println("<input type=\"hidden\" name=\"to_id\" value=\"" + toid + "\"/>");

                    Stop fixedDestStop = getStopById(conn, toid);
                    if (fixedDestStop != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">到着 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedDestStop.name) + " (" + esc(fixedDestStop.type)
                                + ")</div>");
                        out.println("</div>");
                    }
                } else {
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"to_id\">到着 (候補)</label>");
                    out.println("<select class=\"select\" id=\"to_id\" name=\"to_id\">");
                    for (StopSearchCandidate c : toCandidates) {
                        out.println("<option value=\"" + c.stopId + "\">" + esc(c.stopName) + " (" + esc(c.stopType)
                                + ")</option>");
                    }
                    out.println("</select>");
                    out.println("</div>");
                }

                // 再検索表示
                out.println("<div class=\"actions\">");
                out.println("<input class=\"btn\" type=\"submit\" value=\"この候補で検索\"/>");
                out.println("<span class=\"muted\">※ 候補を選んで再検索します</span>");
                out.println("</div>");

                out.println("</form>");
                return;
            }

            // -----------------------------------------------------------------------------------------------

            // 経路探索 (最重要)

            // ---- part 0 (前情報整理) ----

            // 出発地/到着地 を確定 -> その検索に入る
            Stop originStop = getStopById(conn, fromid);
            Stop destStop = getStopById(conn, toid);

            session.setAttribute("lastOriginStopName", originStop != null ? originStop.name : "");
            session.setAttribute("lastOriginStopType", originStop != null ? originStop.type : "");
            session.setAttribute("lastDestStopName", destStop != null ? destStop.name : "");
            session.setAttribute("lastDestStopType", destStop != null ? destStop.type : "");

            // 出発地 / 目的地 の近くの停留所を探索
            List<NearbyStop> stopsNearOrigin = nearbyStopsById(conn, fromid, FROM_RADIUS_M, NEAR_LIMIT);
            List<NearbyStop> stopsNearDest = nearbyStopsById(conn, toid, TO_RADIUS_M, NEAR_LIMIT);

            // 出発地 / 目的地 の近くのポートを探索
            List<PortCandidate> portsNearOrigin = nearbyPorts(conn, originStop.lat, originStop.lon, FROM_RADIUS_M,
                    PORT_LIMIT, true, false); // 借りれる自転車がある
            List<PortCandidate> portsNearDest = nearbyPorts(conn, destStop.lat, destStop.lon, TO_RADIUS_M, PORT_LIMIT,
                    false, true); // 返せるポートが空いている

            // 探索する候補数の上限
            final int TRANSFER_CANDIDATE_LIMIT = RESULT_LIMIT * 30;
            final int DIRECT_CANDIDATE_LIMIT = RESULT_LIMIT * 30;

            // 結果全体を入れるリスト
            List<ResultItem> results = new ArrayList<>();

            // -------------------------

            // ---- part 1 (徒歩のみ) ----

            double dist = distanceMeters(originStop.lat, originStop.lon, destStop.lat, destStop.lon);
            int walkOnlyMin = walkingMinutes(dist, METER_CORRECTION, METER_PER_MINUTE);
            String walkOnlyEnd = addMinutes(baseTime, walkOnlyMin);

            results.add(new ResultItem(
                    0, walkOnlyEnd, walkOnlyMin, "",
                    new WalkOnlyPlan(originStop.name, destStop.name, (int) Math.round(dist), walkOnlyMin, baseTime,
                            walkOnlyEnd)));

            // -------------------------

            // ---- part 2 (自転車のみ) ----

            // 自転車直通プラン候補数上限
            final int BIKE_DIRECT_LIMIT = RESULT_LIMIT * 2;
            List<BikeDirectPlan> bikeDirectCandidates = new ArrayList<>();

            // 出発地近くのポートに対して
            for (PortCandidate fromPort : portsNearOrigin) {

                int walkToStartPortMin = walkingMinutes(fromPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                int walkToStartPortDistance = distanceMeters(originStop.lat, originStop.lon, fromPort.lat,
                        fromPort.lon);
                WalkPath walkToStartPort = new WalkPath(originStop.name, fromPort.portName, walkToStartPortDistance,
                        walkToStartPortMin);

                String bikeStartTime = addMinutes(addMinutes(baseTime, walkToStartPortMin), BIKE_UNLOCK_MIN);
                // 目的地近くのポートに対して
                for (PortCandidate toPort : portsNearDest) {

                    // 適切な自転車かチェック
                    int rideDistance = distanceMeters(fromPort.lat, fromPort.lon, toPort.lat, toPort.lon);
                    if (rideDistance > BIKE_MAX_RIDE_M)
                        continue;
                    if (fromPort.operatorId != toPort.operatorId)
                        continue;
                    if (fromPort.portId == toPort.portId)
                        continue;

                    int rideMin = cyclingMinutes(rideDistance, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                    String bikeEndTime = addMinutes(addMinutes(bikeStartTime, rideMin), BIKE_LOCK_MIN);

                    int walkToDestinationDistance = distanceMeters(toPort.lat, toPort.lon, destStop.lat, destStop.lon);
                    int walkToDestinationMin = walkingMinutes(walkToDestinationDistance, METER_CORRECTION,
                            METER_PER_MINUTE);
                    WalkPath walkToDestination = new WalkPath(toPort.portName, destStop.name, walkToDestinationDistance,
                            walkToDestinationMin);
                    String endTime = addMinutes(bikeEndTime, walkToDestinationMin);
                    int totalMin = minutesBetween(baseTime, endTime);

                    // 自転車移動の情報
                        String operatorContact = normalizeContact(fromPort.operatorContact, fromPort.operatorName);
                        BikePath bike = new BikePath(fromPort.operatorId, fromPort.operatorName, operatorContact,
                            fromPort.portName, toPort.portName,
                            rideDistance, rideMin, bikeStartTime, bikeEndTime);

                    // 移動全体の情報
                    BikeDirectPlan plan = new BikeDirectPlan(walkToStartPort, bike, walkToDestination, totalMin,
                            baseTime, endTime);
                    bikeDirectCandidates.add(plan);
                }
            }

            // 到着が速い順でソート
            bikeDirectCandidates.sort(
                    Comparator.comparing((BikeDirectPlan p) -> LocalTime.parse(p.endTime))
                            .thenComparingInt(p -> p.totalMinutes));

            // 上位 l 件を results へ追加
            int l = Math.min(BIKE_DIRECT_LIMIT, bikeDirectCandidates.size());
            for (int i = 0; i < l; i++) {
                BikeDirectPlan p = bikeDirectCandidates.get(i);
                results.add(new ResultItem(1, p.endTime, p.totalMinutes, "", p));
            }

            // ----------------------------

            // ---- part 3 (公共交通 直通) ----

            // 重複を避けるため、trip_id 毎に最良のものだけ残す用
            java.util.Map<Integer, DirectPlan> bestDirectPlanByTripId = new java.util.HashMap<>();

            // 出発地の近くの停留所に対して
            for (NearbyStop boardStop : stopsNearOrigin) {

                int walkToBoardStopMin = walkingMinutes(boardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop = new WalkPath(originStop.name, boardStop.name, boardStop.distance,
                        walkToBoardStopMin);

                // 目的地の近くの停留所に対して
                for (NearbyStop alightStop : stopsNearDest) {

                    // directPath : tripId, routeName, tripName, fromStopId, fromStopName, depTime,
                    // toStopId, toStopName, arrTime
                    List<DirectPath> directPathCandidates = searchDirect(conn, boardStop.stopId, alightStop.stopId,
                            arrivalTimeToBoardStop, day, 1);

                    if (directPathCandidates.isEmpty())
                        continue;
                    DirectPath leg = directPathCandidates.get(0); // リストの最初の1個だけ取る (1個しかないはずだが)

                    int walkToDestMin = walkingMinutes(alightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                    String originDepartTime = addMinutes(leg.depTime, -walkToBoardStopMin);
                    String destArrivalTime = addMinutes(leg.arrTime, walkToDestMin);
                    WalkPath walkToDest = new WalkPath(alightStop.name, destStop.name, alightStop.distance,
                            walkToDestMin);
                    int totalMin = minutesBetween(originDepartTime, destArrivalTime);

                    DirectPlan directPlan = new DirectPlan(walkToBoardStop, leg, walkToDest, totalMin, originDepartTime,
                            destArrivalTime);
                    // 同じ便なら最良の1個だけ残す (停留所の違いを吸収)
                    DirectPlan currentBestPlan = bestDirectPlanByTripId.get(leg.tripId); // tripid で既存の最良プランを取得
                    if (currentBestPlan == null || betterDirect(directPlan, currentBestPlan))
                        bestDirectPlanByTripId.put(leg.tripId, directPlan);

                    // 増えすぎたら早いものだけ残す -> DIRECT_CANDIDATE_LIMIT 件以上になることを防ぐ
                    if (bestDirectPlanByTripId.size() > DIRECT_CANDIDATE_LIMIT) {
                        java.util.List<DirectPlan> sortPlans = new java.util.ArrayList<>(
                                bestDirectPlanByTripId.values());
                        sortPlans.sort(java.util.Comparator.comparing(p -> LocalTime.parse(p.endTime)));
                        sortPlans.subList(DIRECT_CANDIDATE_LIMIT, sortPlans.size()).clear(); // 上位 N 件以外を削除
                        bestDirectPlanByTripId.clear(); // 入れなおし
                        for (DirectPlan p : sortPlans)
                            bestDirectPlanByTripId.put(p.leg.tripId, p);
                    }
                }
            }

            // results へ追加
            List<DirectPlan> resultDirectPlans = new ArrayList<>(bestDirectPlanByTripId.values());
            resultDirectPlans.sort(Comparator.comparing(p -> LocalTime.parse(p.endTime)));
            for (DirectPlan dp : resultDirectPlans) {
                results.add(new ResultItem(1, dp.endTime, dp.totalMinutes, "", dp));
            }

            // ----------------------------

            // ---- part 4 (公共交通 乗換1回) ----

            java.util.Set<String> seenTransferKeys = new java.util.HashSet<>();
            List<TransferPath> transferPathCandidates = new ArrayList<>();

            // 出発地の近くの停留所に対して
            for (NearbyStop firstBoardStop : stopsNearOrigin) {

                int walkTo1BoardStopMin = walkingMinutes(firstBoardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeTo1BoardStop = addMinutes(baseTime, walkTo1BoardStopMin);
                WalkPath walkTo1BoardStop = new WalkPath(originStop.name, firstBoardStop.name, firstBoardStop.distance,
                        walkTo1BoardStopMin);

                // 乗車した停留所から移動できる停留所 (乗換降車候補) を探索
                List<AlightStopCandidate> firstAlightStopCandidates = listTransferCandidates(conn,
                        firstBoardStop.stopId, arrivalTimeTo1BoardStop, day, MID_LIMIT);

                // 乗換降車する候補の停留所に対して
                for (AlightStopCandidate firstAlightStop : firstAlightStopCandidates) {

                    List<DirectPath> leg1Candidates = searchDirect(conn, firstBoardStop.stopId, firstAlightStop.StopId,
                            arrivalTimeTo1BoardStop, day, 1);
                    if (leg1Candidates.isEmpty())
                        continue;
                    DirectPath leg1 = leg1Candidates.get(0); // 1個だけ取る (1個しかないはずだが)

                    String originDepartTime = addMinutes(leg1.depTime, -walkTo1BoardStopMin);
                    List<NearbyStop> stopsNearFirstAlight = nearbyStopsById(conn, firstAlightStop.StopId,
                            TRANSFER_RADIUS_M, NEAR_LIMIT);

                    // 乗換乗車する候補の停留所に対して
                    for (NearbyStop secondBoardStop : stopsNearFirstAlight) {

                        int walkTransferMin = walkingMinutes(secondBoardStop.distance, METER_CORRECTION,
                                METER_PER_MINUTE);
                        WalkPath walkTransfer = new WalkPath(firstAlightStop.StopName, secondBoardStop.name,
                                secondBoardStop.distance, walkTransferMin);
                        String reachForSecondBoard = addMinutes(leg1.arrTime, TRANSFER_MIN + walkTransferMin);

                        TransferPath bestTransferPathThisCase = null;

                        for (NearbyStop secondAlightStop : stopsNearDest) {

                            int walkToDestMin = walkingMinutes(secondAlightStop.distance, METER_CORRECTION,
                                    METER_PER_MINUTE);
                            List<DirectPath> leg2list = searchDirect(conn, secondBoardStop.stopId,
                                    secondAlightStop.stopId, reachForSecondBoard, day, 1);
                            if (leg2list.isEmpty())
                                continue;
                            DirectPath leg2 = leg2list.get(0);

                            String destArrivalTime = addMinutes(leg2.arrTime, walkToDestMin);
                            WalkPath walkToDest = new WalkPath(secondAlightStop.name, destStop.name,
                                    secondAlightStop.distance, walkToDestMin);

                            int totalMin = minutesBetween(originDepartTime, destArrivalTime);

                            String key = leg1.tripId + ":" + leg1.fromStopId + ":" + leg1.toStopId + "|" + leg2.tripId
                                    + ":" + leg2.fromStopId + ":" + leg2.toStopId;
                            if (!seenTransferKeys.add(key))
                                continue; // 乗車の組み合わせが同じならスキップ (完全に同じなので)

                            TransferPath transferPlanCandidate = new TransferPath(walkTo1BoardStop, leg1, walkTransfer,
                                    leg2, walkToDest, totalMin, originDepartTime, destArrivalTime);
                            if (bestTransferPathThisCase == null || LocalTime.parse(transferPlanCandidate.endTime)
                                    .isBefore(LocalTime.parse(bestTransferPathThisCase.endTime))) {
                                bestTransferPathThisCase = transferPlanCandidate;
                            }
                        }

                        if (bestTransferPathThisCase != null) {
                            if (bestTransferPathThisCase.leg1.routeName != null
                                    && bestTransferPathThisCase.leg1.routeName
                                            .equals(bestTransferPathThisCase.leg2.routeName)) {
                                continue;
                            }
                            transferPathCandidates.add(bestTransferPathThisCase);
                        }
                    }
                }
            }

            // 乗換候補も results に入れる
            for (TransferPath tp : transferPathCandidates) {
                results.add(new ResultItem(2, tp.endTime, tp.totalMinutes, tp.leg1.routeName, tp));
            }

            // ----------------------------

            // ---- part 5 (公共交通 -> 自転車) ----

            java.util.Set<String> seenTBKeys = new java.util.HashSet<>();

            // 目的地近くのポートを運営者別にグループ分け
            java.util.Map<Integer, java.util.List<PortCandidate>> toPortsByOp = groupPortsByOperator(portsNearDest);

            int addedTB = 0;

            outerTB:

            // 出発地近くの停留所に対して
            for (NearbyStop BoardStop : stopsNearOrigin) {

                int walkToBoardStopMin = walkingMinutes(BoardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop = new WalkPath(originStop.name, BoardStop.name, BoardStop.distance,
                        walkToBoardStopMin);
                List<AlightStopCandidate> firstAlightStopCandidates = listTransferCandidates(conn, BoardStop.stopId,
                        arrivalTimeToBoardStop, day, MID_LIMIT);

                // 乗換降車する候補の停留所に対して
                for (AlightStopCandidate firstAlightStop : firstAlightStopCandidates) {

                    List<DirectPath> leg1Candidates = searchDirect(conn, BoardStop.stopId, firstAlightStop.StopId,
                            arrivalTimeToBoardStop, day, 1);
                    if (leg1Candidates.isEmpty())
                        continue;
                    DirectPath leg1 = leg1Candidates.get(0);

                    String originDepartTime = addMinutes(leg1.depTime, -walkToBoardStopMin);

                    List<PortCandidate> startPortCandidates = nearbyPorts(conn, firstAlightStop.lat,
                            firstAlightStop.lon, BIKE_PORT_RADIUS_M, PORT_LIMIT, true, false);

                    // 出発ポートに対して
                    for (PortCandidate startPort : startPortCandidates) {

                        java.util.List<PortCandidate> returnPortCandidates = toPortsByOp.get(startPort.operatorId);
                        if (returnPortCandidates == null)
                            continue;

                        int walkTransferDistance = distanceMeters(firstAlightStop.lat, firstAlightStop.lon,
                                startPort.lat, startPort.lon);
                        int walkTransferMin = walkingMinutes(walkTransferDistance, METER_CORRECTION, METER_PER_MINUTE);
                        WalkPath walkTransfer = new WalkPath(firstAlightStop.StopName, startPort.portName,
                                walkTransferDistance, walkTransferMin);
                        String bikeStartTime = addMinutes(leg1.arrTime,
                                TRANSFER_MIN + walkTransferMin + BIKE_UNLOCK_MIN);

                        // 返却ポートに対して
                        for (PortCandidate returnPort : returnPortCandidates) {
                            if (startPort.portId == returnPort.portId)
                                continue;

                            int rideDist = distanceMeters(startPort.lat, startPort.lon, returnPort.lat, returnPort.lon);
                            if (rideDist > BIKE_MAX_RIDE_M)
                                continue;

                            int rideMin = cyclingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                            String bikeEndTime = addMinutes(bikeStartTime, rideMin + BIKE_LOCK_MIN);

                            int walkToDestDistance = (int) Math
                                    .round(distanceMeters(returnPort.lat, returnPort.lon, destStop.lat, destStop.lon));
                            int walkToDestMin = walkingMinutes(walkToDestDistance, METER_CORRECTION, METER_PER_MINUTE);
                            WalkPath walkToDest = new WalkPath(returnPort.portName, destStop.name, walkToDestDistance,
                                    walkToDestMin);

                            String arrivalTimeToDest = addMinutes(bikeEndTime, walkToDestMin);
                            int totalMin = minutesBetween(originDepartTime, arrivalTimeToDest);

                            String key = "TB:" + leg1.tripId + "|" + startPort.operatorId + ":" + startPort.portId
                                    + "->" + returnPort.portId;
                            if (!seenTBKeys.add(key))
                                continue;

                            String operatorContact = normalizeContact(startPort.operatorContact, startPort.operatorName);
                            BikePath bike = new BikePath(startPort.operatorId, startPort.operatorName,
                                    operatorContact,
                                    startPort.portName, returnPort.portName,
                                    rideDist, rideMin, bikeStartTime, bikeEndTime);

                            TransferTransitBike plan = new TransferTransitBike(walkToBoardStop, leg1, walkTransfer,
                                    bike, walkToDest, totalMin, originDepartTime, arrivalTimeToDest);

                            results.add(new ResultItem(2, arrivalTimeToDest, totalMin, leg1.routeName, plan));

                            if (++addedTB >= TRANSFER_CANDIDATE_LIMIT)
                                break outerTB;
                        }
                    }
                }
            }

            // ----------------------------

            // ---- part 6 (自転車 -> 公共交通) ----

            java.util.Set<String> seenBTKeys = new java.util.HashSet<>();

            List<PortCandidate> usePortsCandidates;
            {
                // 出発地から自転車圏内にある停留所（候補）
                // 件数は多いと重いので 40〜60 程度が無難
                List<NearbyStop> boardStopCandidates = nearbyStopsByLatLon(conn, originStop.lat, originStop.lon,
                        BIKE_MAX_RIDE_M, 50);

                // 目的地側の停留所（近い順に上位だけ）を軽くチェックして、
                // 「目的地方面へ直通がありそうな停留所」だけ残す（重すぎたらこのチェックごと消してOK）
                List<NearbyStop> nearDestTop = stopsNearDest.subList(0, Math.min(8, stopsNearDest.size()));
                List<NearbyStop> goodBoards = new ArrayList<>();
                for (NearbyStop b : boardStopCandidates) {
                    boolean ok = false;
                    for (NearbyStop nsto : nearDestTop) {
                        if (!searchDirect(conn, b.stopId, nsto.stopId, baseTime, day, 1).isEmpty()) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok)
                        goodBoards.add(b);
                }

                // goodBoards の周りの「返却できるポート（free_docks>0）」を集める（portIdで重複除去）
                usePortsCandidates = collectNearbyPortsFromStops(conn, goodBoards, BIKE_PORT_RADIUS_M, PORT_LIMIT,
                        false, true);

                // フォールバック：もし0件なら従来方式（出発地から半径で拾う）も使う
                if (usePortsCandidates.isEmpty()) {
                    usePortsCandidates = nearbyPorts(conn, originStop.lat, originStop.lon, BIKE_MAX_RIDE_M, 30, false,
                            true);
                }
            }

            int addedBT = 0;

            // 目的地側の停留所も上位だけ見る（重いなら 15〜25 推奨）
            List<NearbyStop> destStopsForBT = stopsNearDest.subList(0, Math.min(25, stopsNearDest.size()));

            outerBT:

            // 出発地近くの出発ポートに対して
            for (PortCandidate startPort : portsNearOrigin) {

                int walkToStartPortMin = walkingMinutes(startPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                WalkPath walkToStartPort = new WalkPath(originStop.name, startPort.portName, startPort.distance,
                        walkToStartPortMin);
                String bikeStart = addMinutes(baseTime, walkToStartPortMin + BIKE_UNLOCK_MIN);

                // 返却ポートに対して
                for (PortCandidate returnPort : usePortsCandidates) {

                    int rideDist = distanceMeters(startPort.lat, startPort.lon, returnPort.lat, returnPort.lon);
                    if (rideDist > BIKE_MAX_RIDE_M)
                        continue;
                    if (startPort.operatorId != returnPort.operatorId)
                        continue;
                    if (startPort.portId == returnPort.portId)
                        continue;

                    int rideMin = cyclingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                    String bikeEndTime = addMinutes(bikeStart, rideMin + BIKE_LOCK_MIN);

                    List<NearbyStop> boardStops = nearbyStopsByLatLon(conn, returnPort.lat, returnPort.lon,
                            TRANSFER_RADIUS_M, 12);

                    // 返却ポート近辺の乗車停留所に対して
                    for (NearbyStop boardstop : boardStops) {

                        int walkTransferMin = walkingMinutes(boardstop.distance, METER_CORRECTION, METER_PER_MINUTE);
                        WalkPath walkTransfer = new WalkPath(returnPort.portName, boardstop.name, boardstop.distance,
                                walkTransferMin);
                        String transitDepartTime = addMinutes(bikeEndTime, TRANSFER_MIN + walkTransferMin);

                        DirectPath bestLeg2 = null;
                        WalkPath bestWalk2 = null;
                        String bestEnd = null;
                        int bestTotal = Integer.MAX_VALUE;

                        // 目的地近くの降車停留所に対して
                        for (NearbyStop AlightStop : destStopsForBT) {

                            List<DirectPath> leg2Candidates = searchDirect(conn, boardstop.stopId, AlightStop.stopId,
                                    transitDepartTime, day, 1);
                            if (leg2Candidates.isEmpty())
                                continue;
                            DirectPath leg2 = leg2Candidates.get(0);

                            int walkToDestMin = walkingMinutes(AlightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                            WalkPath walkToDest = new WalkPath(AlightStop.name, destStop.name, AlightStop.distance,
                                    walkToDestMin);

                            String endTime = addMinutes(leg2.arrTime, walkToDestMin);
                            int totalMin = minutesBetween(baseTime, endTime);

                            if (bestEnd == null || LocalTime.parse(endTime).isBefore(LocalTime.parse(bestEnd))) {
                                bestEnd = endTime;
                                bestTotal = totalMin;
                                bestLeg2 = leg2;
                                bestWalk2 = walkToDest;
                            }
                        }

                        if (bestLeg2 == null)
                            continue;

                        String key = "BT:" + startPort.operatorId + ":" + startPort.portId + "->" + returnPort.portId
                                + "|" + bestLeg2.tripId + ":" + bestLeg2.fromStopId + ":" + bestLeg2.toStopId;
                        if (!seenBTKeys.add(key))
                            continue;

                        String operatorContact = normalizeContact(startPort.operatorContact, startPort.operatorName);
                        BikePath bike = new BikePath(
                            startPort.operatorId,
                            startPort.operatorName,
                            operatorContact,
                            startPort.portName,
                            returnPort.portName,
                            rideDist, rideMin, bikeStart, bikeEndTime);

                        TransferBikeTransit plan = new TransferBikeTransit(walkToStartPort, bike, walkTransfer,
                                bestLeg2, bestWalk2, bestTotal, baseTime, bestEnd);

                        results.add(new ResultItem(2, bestEnd, bestTotal, bestLeg2.routeName, plan));

                        if (++addedBT >= TRANSFER_CANDIDATE_LIMIT)
                            break outerBT;
                    }
                }
            }

            // ----------------------------

            // ---- part final (結果全体のソート) ----

            // 最終ソート（到着が早い順 -> 所要時間が短い順 -> 直通有線）
            results.sort(
                    Comparator.comparing((ResultItem r) -> r.end)
                            .thenComparingInt(r -> r.totalMinutes)
                            .thenComparingInt(r -> r.kind));

            // 結果を HTML で表示
            out.println("<h3 class=\"result-title\">経路 : "
                    + esc(originStop.name)
                    + "<span class=\"arrow\">→</span>"
                    + esc(destStop.name)
                    + "</h3>");
            out.println("<p class=\"muted\">（指定時刻以降に出発する便から、到着が早い順に表示）</p>");

            // 表を表示するためのフォーマット
            out.println("<div class=\"table-wrap\">");
            out.println("<table>");
            out.println("<tr>"
                    + "<th>経路</th>"
                    + "<th>時刻</th>"
                    + "<th>所要時間</th>"
                    + "<th>詳細</th>"
                    + "</tr>");

            // 表示
            java.util.Set<String> usedFirstRoute = new java.util.HashSet<>();
            int shown = 0;

            List<ResultItem> displayed = new ArrayList<>();
            session.setAttribute("lastSearchQuery", request.getQueryString());

            for (ResultItem resultItem : results) {
                if (shown >= RESULT_LIMIT)
                    break;

                if (resultItem.kind == 2) {
                    if (resultItem.firstRoute != null && !resultItem.firstRoute.isEmpty()) {
                        if (!usedFirstRoute.add(resultItem.firstRoute))
                            continue;
                    }
                }

                int rid = displayed.size();
                displayed.add(resultItem);

                String detailUrl = request.getContextPath() + "/routesearch?view=detail&rid=" + rid;

                if (resultItem.payload instanceof WalkOnlyPlan) {
                    printWalkOnlyRow(out, (WalkOnlyPlan) resultItem.payload, detailUrl);
                } else if (resultItem.payload instanceof DirectPlan) {
                    printDirectRow(out, (DirectPlan) resultItem.payload, detailUrl);
                } else if (resultItem.payload instanceof BikeDirectPlan) {
                    printBikeDirectRow(out, (BikeDirectPlan) resultItem.payload, detailUrl);
                } else if (resultItem.payload instanceof TransferTransitBike) {
                    printTransitBikeRow(out, (TransferTransitBike) resultItem.payload, detailUrl);
                } else if (resultItem.payload instanceof TransferBikeTransit) {
                    printBikeTransitRow(out, (TransferBikeTransit) resultItem.payload, detailUrl);
                } else {
                    printTransferRow(out, (TransferPath) resultItem.payload, detailUrl);
                }

                shown++;
            }

            session.setAttribute("lastDisplayedResults", displayed);

            // -----------------------------------------------------------------------------------------------

            // 結果閉じる
            out.println("</table>");
            out.println("</div>");

            out.println("<br/>");

            // 例外処理 (DB関係)
        } catch (Exception e) {
            out.println("<pre>エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
        } finally {
            try {
                if (rs != null)
                    rs.close();
            } catch (SQLException e) {
            }
            try {
                if (ps != null)
                    ps.close();
            } catch (SQLException e) {
            }
            try {
                if (conn != null)
                    conn.close();
            } catch (SQLException e) {
            }
        }

        out.println("</div>"); // card
        out.println("</div>"); // app
        out.println("</body>");
        out.println("</html>");
    }

    // その他
    protected void doPost(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    public void destroy() {
    }

    // -----------------------------------------------------------------------------------------------

    // --------------------- 便利関数系 -------------------

    // 詳細ページの表示
    private void renderDetailPage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        HttpSession session = req.getSession(false);
        if (session == null) {
            out.println("セッション切れ");
            return;
        }

        @SuppressWarnings("unchecked")
        List<ResultItem> displayed = (List<ResultItem>) session.getAttribute("lastDisplayedResults");
        if (displayed == null) {
            out.println("検索結果がありません");
            return;
        }

        int rid;
        try {
            rid = Integer.parseInt(req.getParameter("rid"));
        } catch (Exception e) {
            out.println("ridが不正");
            return;
        }
        if (rid < 0 || rid >= displayed.size()) {
            out.println("不正なrid");
            return;
        }

        ResultItem item = displayed.get(rid);

        // 戻るリンク（条件保持）
        String q = (String) session.getAttribute("lastSearchQuery");
        String backUrl = req.getContextPath() + "/routesearch" + (q != null ? ("?" + q) : "");

        // --- HTML（おしゃれなカードUI＋タイムライン表示）---
        out.println("<!DOCTYPE html><html lang='ja'><head>");
        out.println("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>Route Detail</title>");
        out.println("<style>");
        out.println(":root{--bg:#f3f6fa;--panel:#fff;--panelSolid:#fff;--text:#222;--muted:#7a869a;--border:#e3e8ee;--hairline:#e3e8ee;--shadow:0 8px 32px rgba(60,80,120,.10);--shadow2:0 2px 8px rgba(60,80,120,.08);--radius:18px;--gap:18px;--primary:#3b82f6;--primary2:#2563eb;--ring:rgba(59,130,246,.18);--accent:#fbbf24;}");
        out.println("body{margin:0;background:var(--bg);color:var(--text);font-family:'Segoe UI',Roboto,'Noto Sans JP',Meiryo,sans-serif;-webkit-font-smoothing:antialiased;moz-osx-font-smoothing:grayscale;}");
        out.println(".detail-card{max-width:960px;margin:32px auto;padding:28px 32px;background:var(--panel);border-radius:20px;box-shadow:var(--shadow);border:1px solid var(--border);}");
        out.println(".detail-header{font-size:22px;font-weight:700;margin-bottom:14px;color:var(--primary2);}");
        out.println(".detail-summary{margin-bottom:18px;}");
        out.println(".summary-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;}");
        out.println(".summary-item{padding:12px 14px;border:1px solid var(--border);border-radius:14px;background:var(--panelSolid);box-shadow:var(--shadow2);}");
        out.println(".summary-label{font-size:12px;color:var(--muted);letter-spacing:.05em;text-transform:uppercase;}");
        out.println(".summary-value{font-size:17px;font-weight:700;letter-spacing:-.01em;}");
        out.println(".summary-sub{margin-top:4px;color:var(--muted);font-size:13px;}");
        out.println(".back-btn{display:inline-block;margin-bottom:18px;padding:10px 22px;background:linear-gradient(90deg, var(--primary), var(--primary2));color:#fff;border-radius:999px;font-weight:700;text-decoration:none;box-shadow:0 4px 12px rgba(59,130,246,.10);transition:filter .12s, box-shadow .12s;}");
        out.println(".back-btn:hover{filter:saturate(1.08);box-shadow:0 8px 24px rgba(59,130,246,.18);}");
        out.println(".back-btn:active{filter:brightness(.98);}");
        out.println(".steps{display:flex;flex-direction:column;gap:14px;margin-top:14px;}");
        out.println(".step{display:grid;grid-template-columns: 88px 1fr;grid-template-rows: auto auto;gap:10px 14px;padding:12px 14px;align-items:start;border:1px solid var(--border);border-radius:16px;background:#fff;box-shadow:0 2px 8px rgba(60,80,120,.08);}");
        out.println(".step .kind{font-weight:800;font-size:13px;letter-spacing:.04em;align-self:center;padding:7px 10px;border-radius:999px;background:rgba(59,130,246,.10);color:var(--primary2);text-align:center;}");
        out.println(".step .main{grid-column:2;grid-row:1;white-space:normal;overflow:hidden;text-overflow:ellipsis;font-size:15px;line-height:1.45;display:flex;align-items:center;gap:8px;}");
        out.println(".step .meta{grid-column: 2;grid-row: 2;display:flex;flex-wrap:wrap;gap:8px;color:var(--muted);font-size:13px;align-items:center;}");
        out.println(".arrow-mini{color:var(--muted);font-weight:900;}");
        out.println(".chip{display:inline-flex;align-items:center;gap:6px;padding:4px 9px;border-radius:999px;border:1px solid var(--border);background:#fff;font-size:12px;font-weight:700;}");
        out.println(".chip .dot{width:10px;height:10px;border-radius:3px;background:var(--primary);}");
        out.println(".chip.line{border-color:var(--line);background:rgba(0,0,0,.02);color:#111;}");
        out.println(".chip.line .dot{background:var(--line);}");
        out.println(".chip.trip{background:rgba(59,130,246,.12);border-color:rgba(59,130,246,.32);color:#1d4ed8;}");
        out.println(".chip.info{font-weight:600;}");
        out.println(".chip.contact{background:rgba(16,185,129,.12);border-color:rgba(16,185,129,.35);color:#065f46;}");
        out.println(".reservation-section{margin-top:28px;padding:22px;background:linear-gradient(135deg,rgba(16,185,129,.12) 0%,rgba(59,130,246,.12) 100%);border-radius:16px;border:2px solid rgba(16,185,129,.35);}");
        out.println(".reservation-title{font-size:16px;font-weight:700;color:#065f46;margin:0 0 14px 0;}");
        out.println(".reservation-actions{display:flex;flex-wrap:wrap;gap:12px;}");
        out.println(".btn-reserve{padding:14px 32px;background:linear-gradient(135deg,#10b981 0%,#059669 100%);color:#fff;border:none;border-radius:999px;font-size:15px;font-weight:700;cursor:pointer;box-shadow:0 4px 14px rgba(16,185,129,.3);transition:all .2s ease;}");
        out.println(".btn-reserve:hover{transform:translateY(-2px);box-shadow:0 6px 20px rgba(16,185,129,.4);}");
        out.println(".btn-reserve:disabled{background:#ccc;cursor:not-allowed;transform:none;box-shadow:none;}");
        out.println(".btn-action{padding:12px 24px;background:#fff;border:2px solid #10b981;color:#065f46;border-radius:999px;font-size:14px;font-weight:700;cursor:pointer;transition:all .2s ease;}");
        out.println(".btn-action:hover{background:#ecfdf5;transform:translateY(-1px);}");
        out.println(".btn-action:disabled{background:#f3f4f6;border-color:#d1d5db;color:#9ca3af;cursor:not-allowed;transform:none;}");
        out.println(".btn-cancel{padding:12px 24px;background:#fff;border:2px solid #ef4444;color:#dc2626;border-radius:999px;font-size:14px;font-weight:700;cursor:pointer;transition:all .2s ease;}");
        out.println(".btn-cancel:hover{background:#fef2f2;transform:translateY(-1px);}");
        out.println(".reservation-status{font-size:13px;color:#059669;font-weight:600;margin-top:10px;}");
        out.println("</style>");
        out.println("</head><body>");

        out.println("<div class='detail-card'>");
        out.println("<a href='" + esc(backUrl) + "' class='back-btn'>← 戻る</a>");
        out.println("<div class='detail-header'>ルート詳細</div>");

        String payloadOrigin = "";
        String payloadDest = "";
        if (item.payload instanceof WalkOnlyPlan) {
            WalkOnlyPlan wp = (WalkOnlyPlan) item.payload;
            payloadOrigin = wp.fromName;
            payloadDest = wp.toName;
        } else if (item.payload instanceof DirectPlan) {
            DirectPlan dp = (DirectPlan) item.payload;
            payloadOrigin = dp.walk0.fromName != null ? dp.walk0.fromName : dp.leg.fromStopName;
            payloadDest = dp.walk2.toName != null ? dp.walk2.toName : dp.leg.toStopName;
        } else if (item.payload instanceof TransferPath) {
            TransferPath tp = (TransferPath) item.payload;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.leg1.fromStopName;
            payloadDest = tp.walk2.toName != null ? tp.walk2.toName : tp.leg2.toStopName;
        } else if (item.payload instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) item.payload;
            payloadOrigin = bp.walk0.fromName != null ? bp.walk0.fromName : bp.bike.fromPortName;
            payloadDest = bp.walk2.toName != null ? bp.walk2.toName : bp.bike.toPortName;
        } else if (item.payload instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) item.payload;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.leg1.fromStopName;
            payloadDest = tp.walk2.toName != null ? tp.walk2.toName : tp.bike.toPortName;
        } else if (item.payload instanceof TransferBikeTransit) {
            TransferBikeTransit tp = (TransferBikeTransit) item.payload;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.bike.fromPortName;
            payloadDest = tp.walk2.toName != null ? tp.walk2.toName : tp.leg2.toStopName;
        }

        String originName = preferNonEmpty(toStr(session.getAttribute("lastOriginStopName")), payloadOrigin);
        String originType = toStr(session.getAttribute("lastOriginStopType"));
        String destName = preferNonEmpty(toStr(session.getAttribute("lastDestStopName")), payloadDest);
        String destType = toStr(session.getAttribute("lastDestStopType"));
        String arrivalTime = item.end != null ? hhmm(item.end.toString()) : "";

        out.println("<div class='detail-summary'>");
        out.println("<div class='summary-grid'>");
        out.println(summaryItem("出発地", originName, originType.isEmpty() ? "" : originType));
        out.println(summaryItem("目的地", destName, destType.isEmpty() ? "" : destType));
        out.println(summaryItem("所要時間", item.totalMinutes + "分", arrivalTime.isEmpty() ? "" : ("到着 " + arrivalTime)));
        out.println("</div>");
        out.println("</div>");

        String arrow = " <span class='arrow-mini'>→</span> ";

        out.println("<div class='steps'>");
        if (item.payload instanceof WalkOnlyPlan) {
            WalkOnlyPlan wp = (WalkOnlyPlan) item.payload;
            String main = esc(wp.fromName) + arrow + esc(wp.toName) + " (" + hhmm(wp.startTime) + "→"
                    + hhmm(wp.endTime) + ")";
            String meta = chipInfo("距離 約" + wp.distanceM + "m") + chipInfo("時間 " + wp.minutes + "分");
            printStep(out, "徒歩", main, meta);
        } else if (item.payload instanceof DirectPlan) {
            DirectPlan dp = (DirectPlan) item.payload;
            if (!isZeroWalk(dp.walk0)) {
                String mainWalk0 = esc(dp.walk0.fromName) + arrow + esc(dp.walk0.toName);
                String metaWalk0 = chipInfo("距離 約" + dp.walk0.dist + "m") + chipInfo("時間 " + dp.walk0.min + "分");
                printStep(out, "徒歩", mainWalk0, metaWalk0);
            }

            String mainRide = esc(dp.leg.fromStopName) + " " + hhmm(dp.leg.depTime) + arrow + esc(dp.leg.toStopName)
                    + " " + hhmm(dp.leg.arrTime);
            String metaRide = chipLine(dp.leg.routeName, dp.leg.routeColor)
                    + chipTrip(dp.leg.tripName)
                    + chipInfo("時間 " + minutesBetween(dp.leg.depTime, dp.leg.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);

            if (!isZeroWalk(dp.walk2)) {
                String mainWalk2 = esc(dp.walk2.fromName) + arrow + esc(dp.walk2.toName);
                String metaWalk2 = chipInfo("距離 約" + dp.walk2.dist + "m") + chipInfo("時間 " + dp.walk2.min + "分");
                printStep(out, "徒歩", mainWalk2, metaWalk2);
            }
        } else if (item.payload instanceof TransferPath) {
            TransferPath tp = (TransferPath) item.payload;
            if (!isZeroWalk(tp.walk0)) {
                String mainWalk0 = esc(tp.walk0.fromName) + arrow + esc(tp.walk0.toName);
                String metaWalk0 = chipInfo("距離 約" + tp.walk0.dist + "m") + chipInfo("時間 " + tp.walk0.min + "分");
                printStep(out, "徒歩", mainWalk0, metaWalk0);
            }

            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow
                    + esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor)
                    + chipTrip(tp.leg1.tripName)
                    + chipInfo("時間 " + minutesBetween(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);

            if (!isZeroWalk(tp.walk1)) {
                String mainWalk1 = esc(tp.walk1.fromName) + arrow + esc(tp.walk1.toName);
                String metaWalk1 = chipInfo("距離 約" + tp.walk1.dist + "m") + chipInfo("時間 " + tp.walk1.min + "分");
                printStep(out, "徒歩", mainWalk1, metaWalk1);
            } else {
                printStep(out, "乗換", "同一地点で乗換", "");
            }

            String mainRide2 = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow
                    + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide2 = chipLine(tp.leg2.routeName, tp.leg2.routeColor)
                    + chipTrip(tp.leg2.tripName)
                    + chipInfo("時間 " + minutesBetween(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide2, metaRide2);

            if (!isZeroWalk(tp.walk2)) {
                String mainWalk2 = esc(tp.walk2.fromName) + arrow + esc(tp.walk2.toName);
                String metaWalk2 = chipInfo("距離 約" + tp.walk2.dist + "m") + chipInfo("時間 " + tp.walk2.min + "分");
                printStep(out, "徒歩", mainWalk2, metaWalk2);
            }
        } else if (item.payload instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) item.payload;
            if (!isZeroWalk(bp.walk0)) {
                String mainWalk0 = esc(bp.walk0.fromName) + arrow + esc(bp.walk0.toName);
                String metaWalk0 = chipInfo("距離 約" + bp.walk0.dist + "m") + chipInfo("時間 " + bp.walk0.min + "分");
                printStep(out, "徒歩", mainWalk0, metaWalk0);
            }

            String mainBike = esc(bp.bike.fromPortName) + " " + hhmm(bp.bike.startTime) + arrow
                    + esc(bp.bike.toPortName) + " " + hhmm(bp.bike.endTime);
            String metaBike = chipInfo("距離 約" + bp.bike.distanceM + "m")
                    + chipInfo("時間 " + bp.bike.rideMinutes + "分")
                    + chipInfo("事業者 " + bp.bike.operatorName)
                    + chipContact(bp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);

            if (!isZeroWalk(bp.walk2)) {
                String mainWalk2 = esc(bp.walk2.fromName) + arrow + esc(bp.walk2.toName);
                String metaWalk2 = chipInfo("距離 約" + bp.walk2.dist + "m") + chipInfo("時間 " + bp.walk2.min + "分");
                printStep(out, "徒歩", mainWalk2, metaWalk2);
            }
        } else if (item.payload instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) item.payload;
            if (!isZeroWalk(tp.walk0)) {
                String mainWalk0 = esc(tp.walk0.fromName) + arrow + esc(tp.walk0.toName);
                String metaWalk0 = chipInfo("距離 約" + tp.walk0.dist + "m") + chipInfo("時間 " + tp.walk0.min + "分");
                printStep(out, "徒歩", mainWalk0, metaWalk0);
            }

            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow
                    + esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor)
                    + chipTrip(tp.leg1.tripName)
                    + chipInfo("時間 " + minutesBetween(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);

            if (!isZeroWalk(tp.walk1)) {
                String mainWalk1 = esc(tp.walk1.fromName) + arrow + esc(tp.walk1.toName);
                String metaWalk1 = chipInfo("距離 約" + tp.walk1.dist + "m") + chipInfo("時間 " + tp.walk1.min + "分");
                printStep(out, "徒歩", mainWalk1, metaWalk1);
            }

            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow
                    + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m")
                    + chipInfo("時間 " + tp.bike.rideMinutes + "分")
                    + chipInfo("事業者 " + tp.bike.operatorName)
                    + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);

            if (!isZeroWalk(tp.walk2)) {
                String mainWalk2 = esc(tp.walk2.fromName) + arrow + esc(tp.walk2.toName);
                String metaWalk2 = chipInfo("距離 約" + tp.walk2.dist + "m") + chipInfo("時間 " + tp.walk2.min + "分");
                printStep(out, "徒歩", mainWalk2, metaWalk2);
            }
        } else if (item.payload instanceof TransferBikeTransit) {
            TransferBikeTransit tp = (TransferBikeTransit) item.payload;
            if (!isZeroWalk(tp.walk0)) {
                String mainWalk0 = esc(tp.walk0.fromName) + arrow + esc(tp.walk0.toName);
                String metaWalk0 = chipInfo("距離 約" + tp.walk0.dist + "m") + chipInfo("時間 " + tp.walk0.min + "分");
                printStep(out, "徒歩", mainWalk0, metaWalk0);
            }

            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow
                    + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m")
                    + chipInfo("時間 " + tp.bike.rideMinutes + "分")
                    + chipInfo("事業者 " + tp.bike.operatorName)
                    + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);

            if (!isZeroWalk(tp.walk1)) {
                String mainWalk1 = esc(tp.walk1.fromName) + arrow + esc(tp.walk1.toName);
                String metaWalk1 = chipInfo("距離 約" + tp.walk1.dist + "m") + chipInfo("時間 " + tp.walk1.min + "分");
                printStep(out, "徒歩", mainWalk1, metaWalk1);
            }

            String mainRide = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow
                    + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide = chipLine(tp.leg2.routeName, tp.leg2.routeColor)
                    + chipTrip(tp.leg2.tripName)
                    + chipInfo("時間 " + minutesBetween(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);

            if (!isZeroWalk(tp.walk2)) {
                String mainWalk2 = esc(tp.walk2.fromName) + arrow + esc(tp.walk2.toName);
                String metaWalk2 = chipInfo("距離 約" + tp.walk2.dist + "m") + chipInfo("時間 " + tp.walk2.min + "分");
                printStep(out, "徒歩", mainWalk2, metaWalk2);
            }
        }
        out.println("</div>");

        // ===== シェアサイクル予約セクション =====
        boolean hasBikeSegment = (item.payload instanceof BikeDirectPlan) ||
                                 (item.payload instanceof TransferTransitBike) ||
                                 (item.payload instanceof TransferBikeTransit);

        if (hasBikeSegment) {
            String bikeOperatorName = "";
            String bikeOperatorContact = "";
            int startPortId = 0;
            int bikeOperatorId = 0;

            if (item.payload instanceof BikeDirectPlan) {
                BikeDirectPlan bp = (BikeDirectPlan) item.payload;
                bikeOperatorName = bp.bike.operatorName;
                bikeOperatorContact = bp.bike.operatorContact;
                startPortId = -1; // ビューから取得されない場合
                bikeOperatorId = bp.bike.operatorId;
            } else if (item.payload instanceof TransferTransitBike) {
                TransferTransitBike tp = (TransferTransitBike) item.payload;
                bikeOperatorName = tp.bike.operatorName;
                bikeOperatorContact = tp.bike.operatorContact;
                startPortId = -1;
                bikeOperatorId = tp.bike.operatorId;
            } else if (item.payload instanceof TransferBikeTransit) {
                TransferBikeTransit tp = (TransferBikeTransit) item.payload;
                bikeOperatorName = tp.bike.operatorName;
                bikeOperatorContact = tp.bike.operatorContact;
                startPortId = -1;
                bikeOperatorId = tp.bike.operatorId;
            }

            out.println("<div class='reservation-section'>");
            out.println("<div class='reservation-title'>🚲 シェアサイクルを予約</div>");
            out.println("<div style='margin-bottom:12px;'>");
            out.println("<p style='margin:0 0 6px 0;color:#065f46;font-size:14px;font-weight:600;'>" + esc(bikeOperatorName) + "</p>");
            out.println("<p style='margin:0;color:#059669;font-size:13px;'>" + esc(bikeOperatorContact) + "</p>");
            out.println("</div>");
            out.println("<div class='reservation-actions'>");
            out.println("<button class='btn-reserve' id='reserveBtn' onclick='reserveBike(" + bikeOperatorId + ")'>予約する</button>");
            out.println("<button class='btn-action' id='startBtn' style='display:none;' onclick='startBikeUsage()'>利用開始</button>");
            out.println("<button class='btn-action' id='returnBtn' style='display:none;' onclick='returnBike()'>返却</button>");
            out.println("<button class='btn-cancel' id='cancelBtn' style='display:none;' onclick='cancelReservation()'>キャンセル</button>");
            out.println("</div>");
            out.println("<div class='reservation-status' id='statusMsg'></div>");
            out.println("</div>");

            out.println("<script>");
            out.println("var reservationState = 'not_reserved';");
            out.println("var currentReservationId = null;");
            out.println("");
            out.println("function reserveBike(operatorId) {");
            out.println("  var xhr = new XMLHttpRequest();");
            out.println("  xhr.open('POST', '" + req.getContextPath() + "/bikereservation', true);");
            out.println("  xhr.setRequestHeader('Content-Type', 'application/json');");
            out.println("  xhr.onreadystatechange = function() {");
            out.println("    if (xhr.readyState === 4) {");
            out.println("      var response = JSON.parse(xhr.responseText);");
            out.println("      if (response.success) {");
            out.println("        currentReservationId = response.reservation_id;");
            out.println("        reservationState = 'reserved';");
            out.println("        updateButtonStates();");
            out.println("        document.getElementById('statusMsg').textContent = '✓ 予約しました。利用を開始してください。';");
            out.println("      } else {");
            out.println("        alert('予約に失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  xhr.send(JSON.stringify({action: 'reserve', operator_id: operatorId}));");
            out.println("}");
            out.println("");
            out.println("function startBikeUsage() {");
            out.println("  if (!currentReservationId) return;");
            out.println("  var xhr = new XMLHttpRequest();");
            out.println("  xhr.open('POST', '" + req.getContextPath() + "/bikereservation', true);");
            out.println("  xhr.setRequestHeader('Content-Type', 'application/json');");
            out.println("  xhr.onreadystatechange = function() {");
            out.println("    if (xhr.readyState === 4) {");
            out.println("      var response = JSON.parse(xhr.responseText);");
            out.println("      if (response.success) {");
            out.println("        reservationState = 'in_use';");
            out.println("        updateButtonStates();");
            out.println("        document.getElementById('statusMsg').textContent = '✓ 利用を開始しました。返却してください。';");
            out.println("      } else {");
            out.println("        alert('利用開始に失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  xhr.send(JSON.stringify({action: 'start', reservation_id: currentReservationId}));");
            out.println("}");
            out.println("");
            out.println("function returnBike() {");
            out.println("  if (!currentReservationId) return;");
            out.println("  var xhr = new XMLHttpRequest();");
            out.println("  xhr.open('POST', '" + req.getContextPath() + "/bikereservation', true);");
            out.println("  xhr.setRequestHeader('Content-Type', 'application/json');");
            out.println("  xhr.onreadystatechange = function() {");
            out.println("    if (xhr.readyState === 4) {");
            out.println("      var response = JSON.parse(xhr.responseText);");
            out.println("      if (response.success) {");
            out.println("        reservationState = 'returned';");
            out.println("        updateButtonStates();");
            out.println("        document.getElementById('statusMsg').textContent = '✓ 自転車を返却しました。ご利用ありがとうございました。';");
            out.println("      } else {");
            out.println("        alert('返却に失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  xhr.send(JSON.stringify({action: 'return', reservation_id: currentReservationId}));");
            out.println("}");
            out.println("");
            out.println("function cancelReservation() {");
            out.println("  if (!currentReservationId) return;");
            out.println("  if (!confirm('予約をキャンセルしますか？')) return;");
            out.println("  var xhr = new XMLHttpRequest();");
            out.println("  xhr.open('POST', '" + req.getContextPath() + "/bikereservation', true);");
            out.println("  xhr.setRequestHeader('Content-Type', 'application/json');");
            out.println("  xhr.onreadystatechange = function() {");
            out.println("    if (xhr.readyState === 4) {");
            out.println("      var response = JSON.parse(xhr.responseText);");
            out.println("      if (response.success) {");
            out.println("        reservationState = 'not_reserved';");
            out.println("        currentReservationId = null;");
            out.println("        updateButtonStates();");
            out.println("        document.getElementById('statusMsg').textContent = '';");
            out.println("      } else {");
            out.println("        alert('キャンセルに失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  xhr.send(JSON.stringify({action: 'cancel', reservation_id: currentReservationId}));");
            out.println("}");
            out.println("");
            out.println("function updateButtonStates() {");
            out.println("  var reserveBtn = document.getElementById('reserveBtn');");
            out.println("  var startBtn = document.getElementById('startBtn');");
            out.println("  var returnBtn = document.getElementById('returnBtn');");
            out.println("  var cancelBtn = document.getElementById('cancelBtn');");
            out.println("");
            out.println("  if (reservationState === 'not_reserved') {");
            out.println("    reserveBtn.style.display = 'inline-block';");
            out.println("    startBtn.style.display = 'none';");
            out.println("    returnBtn.style.display = 'none';");
            out.println("    cancelBtn.style.display = 'none';");
            out.println("  } else if (reservationState === 'reserved') {");
            out.println("    reserveBtn.style.display = 'none';");
            out.println("    startBtn.style.display = 'inline-block';");
            out.println("    returnBtn.style.display = 'none';");
            out.println("    cancelBtn.style.display = 'inline-block';");
            out.println("  } else if (reservationState === 'in_use') {");
            out.println("    reserveBtn.style.display = 'none';");
            out.println("    startBtn.style.display = 'none';");
            out.println("    returnBtn.style.display = 'inline-block';");
            out.println("    cancelBtn.style.display = 'none';");
            out.println("  } else if (reservationState === 'returned') {");
            out.println("    reserveBtn.style.display = 'none';");
            out.println("    startBtn.style.display = 'none';");
            out.println("    returnBtn.style.display = 'none';");
            out.println("    cancelBtn.style.display = 'none';");
            out.println("  }");
            out.println("}");
            out.println("</script>");
        }

        out.println("</div>");
        out.println("</body></html>");
    }

    private String toStr(Object obj) {
        return obj == null ? "" : obj.toString();
    }

    private String preferNonEmpty(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    // 文字エラー対策1
    private String esc(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // 文字エラー対策2
    private String option(String value, String label, String current) {
        String selected = (current != null && current.equals(value)) ? " selected" : "";
        return "<option value=\"" + esc(value) + "\"" + selected + ">" + esc(label) + "</option>";
    }

    // 時間管理の関数
    private String addMinutes(String HHmm, int minutes) {
        LocalTime time = LocalTime.parse(HHmm); // "HH:mm"
        return time.plusMinutes(minutes).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // HH:mm 統一
    private String hhmm(String t) {
        if (t == null)
            return "";
        return (t.length() >= 5) ? t.substring(0, 5) : t;
    }

    // 時刻の間を返す
    private int minutesBetween(String startHHmm, String endHHmm) {
        LocalTime s = LocalTime.parse(startHHmm);
        LocalTime e = LocalTime.parse(endHHmm);
        long m = java.time.Duration.between(s, e).toMinutes();
        if (m < 0)
            m += 24 * 60;
        return (int) m;
    }

    // --------------------- 基本系 --------------------

    // 緯度経度 -> 距離 (メートル)
    private int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double diflat = Math.toRadians(lat2 - lat1);
        double diflon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(diflat / 2) * Math.sin(diflat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(diflon / 2)
                        * Math.sin(diflon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) (R * c);
    }

    // 距離 -> 徒歩時間
    private int walkingMinutes(double meters, double meter_correction, double walk_meter_per_minutes) {
        if (meters <= 0)
            return 1;
        int minutes = (int) Math.ceil(meters * meter_correction / walk_meter_per_minutes);
        return Math.max(1, minutes);
    }

    // 距離 -> 自転車時間
    private int cyclingMinutes(int meters, double bike_meter_correction, double bike_meter_per_minutes) {
        if (meters <= 0)
            return 1;
        int minutes = (int) Math.ceil(meters * bike_meter_correction / bike_meter_per_minutes);
        return Math.max(1, minutes);
    }

    // --------------------- 候補検索系 --------------------

    // 停留所の情報
    private static class Stop {
        final String name;
        final String type;
        final double lat;
        final double lon;

        Stop(String name, String type, double lat, double lon) {
            this.name = name;
            this.type = type;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // stopId から停留所情報を取得
    private Stop getStopById(Connection conn, int stopId) throws SQLException {
        String sql = "SELECT stop_id, stop_name, stop_latitude, stop_longitude, stop_type "
                + "FROM stop_information "
                + "WHERE stop_id = ? "
                + "LIMIT 1";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, stopId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return null;

                String name = rs.getString("stop_name");
                double lat = rs.getDouble("stop_latitude");
                double lon = rs.getDouble("stop_longitude");
                String type = rs.getString("stop_type");
                return new Stop(name, type, lat, lon);
            }
        }
    }

    // 出発地/目的地 の候補
    private static class StopSearchCandidate {
        final int stopId;
        final String stopName;
        final String stopType;

        StopSearchCandidate(int stopId, String stopName, String stopType) {
            this.stopId = stopId;
            this.stopName = stopName;
            this.stopType = stopType;
        }
    }

    // 候補の前検索
    private List<StopSearchCandidate> searchStopCandidates(Connection conn, String keyword, int limit)
            throws SQLException {
        String sql = "SELECT stop_id, stop_name, stop_type "
                + "FROM stop_information "
                + "WHERE stop_name ILIKE ? "
                + "ORDER BY CASE "
                + "WHEN stop_name = ? THEN 0 "
                + "WHEN stop_name ILIKE ? THEN 1 "
                + "ELSE 2 END, "
                + "CHAR_LENGTH(stop_name) ASC "
                + "LIMIT ? ";

        List<StopSearchCandidate> list = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, "%" + keyword + "%"); // 部分一致
            ps.setString(idx++, keyword); // 完全一致
            ps.setString(idx++, keyword + "%"); // 前方一致
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int stopId = rs.getInt("stop_id");
                    String name = rs.getString("stop_name");
                    String type = rs.getString("stop_type");
                    list.add(new StopSearchCandidate(stopId, name, type));
                }
            }
        }

        return list;
    }

    // --------------------- 経路探索系 --------------------

    // 移動1回の動き
    private static class DirectPath {
        final int tripId;
        final String routeName;
        final String routeColor;
        final String tripName;
        final int fromStopId;
        final String fromStopName;
        final String depTime; // "HH:mm"
        final int toStopId;
        final String toStopName;
        final String arrTime; // "HH:mm"

        DirectPath(int tripId, String routeName, String routeColor, String tripName,
                int fromStopId, String fromStopName, String depTime,
                int toStopId, String toStopName, String arrTime) {
            this.tripId = tripId;
            this.routeName = routeName;
            this.routeColor = routeColor;
            this.tripName = tripName;
            this.fromStopId = fromStopId;
            this.fromStopName = fromStopName;
            this.depTime = depTime;
            this.toStopId = toStopId;
            this.toStopName = toStopName;
            this.arrTime = arrTime;
        }
    }

    // 直通の検索
    private List<DirectPath> searchDirect(Connection conn, int fromStopId, int toStopId,
            String baseTime, String day, int limit) throws SQLException {

        String sql = ""
                + "SELECT "
                + "  t.trip_id AS trip_id, "
                + "  r.route_name AS route_name, "
                + "  r.route_color AS route_color, "
                + "  t.trip_name AS trip_name, "
                + "  sa_from.stop_id AS from_stop_id, "
                + "  sf.stop_name AS from_stop_name, "
                + "  sa_from.departure_time AS dep_time, "
                + "  sa_to.stop_id AS to_stop_id, "
                + "  st.stop_name AS to_stop_name, "
                + "  sa_to.arrival_time AS arr_time "
                + "FROM stop_at sa_from "
                + "JOIN stop_information sf ON sf.stop_id = sa_from.stop_id "
                + "JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id "
                + "JOIN stop_information st ON st.stop_id = sa_to.stop_id "
                + "JOIN trip_information t ON t.trip_id = sa_from.trip_id "
                + "JOIN route_trip rt ON rt.trip_id = t.trip_id "
                + "JOIN route_information r ON r.route_id = rt.route_id "
                + "WHERE sa_from.stop_id = ? "
                + "  AND sa_to.stop_id = ? "
                + "  AND sa_from.arrival_order < sa_to.arrival_order "
                + "  AND sa_from.departure_time >= ?::time ";

        if ("平日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','平日') ";
        } else if ("休日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','休日') ";
        }

        sql += " ORDER BY sa_to.arrival_time ASC, sa_from.departure_time ASC ";
        sql += " LIMIT ?";

        List<DirectPath> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setInt(idx++, toStopId);
            ps.setString(idx++, baseTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DirectPath(
                            rs.getInt("trip_id"),
                            rs.getString("route_name"),
                            rs.getString("route_color"),
                            rs.getString("trip_name"),
                            rs.getInt("from_stop_id"),
                            rs.getString("from_stop_name"),
                            hhmm(rs.getString("dep_time")),
                            rs.getInt("to_stop_id"),
                            rs.getString("to_stop_name"),
                            hhmm(rs.getString("arr_time"))));
                }
            }
        }
        return list;
    }

    // 徒歩移動のクラス (出発地, 目的地, 距離, 分)
    private static class WalkPath {
        final String fromName;
        final String toName;
        final int dist;
        final int min;

        WalkPath(String fromName, String toName, int dist, int min) {
            this.fromName = fromName;
            this.toName = toName;
            this.dist = dist;
            this.min = min;
        }
    }

    // 乗換降車候補の停留所
    private static class AlightStopCandidate {
        final int StopId;
        final String StopName;
        final double lat;
        final double lon;

        AlightStopCandidate(int StopId, String StopName, double lat, double lon) {
            this.StopId = StopId;
            this.StopName = StopName;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // 出発停留所から降りれる停留所を列挙
    private List<AlightStopCandidate> listTransferCandidates(
            Connection conn, int fromStopId, String baseTime, String day, int limit) throws SQLException {

        String sql = ""
                + "SELECT DISTINCT "
                + "  t.trip_id AS trip_id, "
                + "  sa_to.stop_id AS mid_stop_id, "
                + "  st.stop_name AS mid_stop_name, "
                + "  st.stop_latitude AS mid_stop_lat, "
                + "  st.stop_longitude AS mid_stop_lon, "
                + "  sa_to.arrival_time AS arr_time "
                + "FROM stop_at sa_from "
                + "JOIN stop_at sa_to ON sa_to.trip_id = sa_from.trip_id "
                + "JOIN stop_information st ON st.stop_id = sa_to.stop_id "
                + "JOIN trip_information t ON t.trip_id = sa_from.trip_id "
                + "WHERE sa_from.stop_id = ? "
                + "  AND sa_from.departure_time >= ?::time "
                + "  AND sa_from.arrival_order < sa_to.arrival_order ";

        if ("平日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','平日') ";
        } else if ("休日".equals(day)) {
            sql += " AND t.trip_datetime IN ('全日','休日') ";
        }

        sql += " ORDER BY sa_to.arrival_time ASC LIMIT ?";

        List<AlightStopCandidate> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setString(idx++, baseTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {

                    list.add(new AlightStopCandidate(
                            rs.getInt("mid_stop_id"),
                            rs.getString("mid_stop_name"),
                            rs.getDouble("mid_stop_lat"),
                            rs.getDouble("mid_stop_lon")));
                }
            }
        }
        return list;
    }

    // より良い乗換か調べる
    private boolean betterDirect(DirectPlan a, DirectPlan b) {
        LocalTime ea = LocalTime.parse(a.endTime);
        LocalTime eb = LocalTime.parse(b.endTime);

        if (ea.isBefore(eb))
            return true;
        if (ea.isAfter(eb))
            return false;

        // 同着なら「最後の徒歩が短い方」を優先（＝手前下車のゴミを落とす）
        int wa = (a.walk2 == null) ? 0 : a.walk2.dist;
        int wb = (b.walk2 == null) ? 0 : b.walk2.dist;
        if (wa != wb)
            return wa < wb;

        // さらに同じなら所要時間が短い方
        return a.totalMinutes < b.totalMinutes;
    }

    // ある停留所の近くの停留所
    private static class NearbyStop {
        final int stopId;
        final String name;
        final int distance;
        final double lat;
        final double lon;

        NearbyStop(int stopId, String name, int distance, double lat, double lon) {
            this.stopId = stopId;
            this.name = name;
            this.distance = distance;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // ある停留所の近くの停留所を列挙
    private List<NearbyStop> nearbyStopsById(Connection conn, int centerStopId, int radiusM, int limit)
            throws SQLException {
        Stop centerstop = getStopById(conn, centerStopId);
        if (centerstop == null)
            return new ArrayList<>();

        // 半径radiusMを緯度経度の範囲に雑に変換（高速化）
        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerstop.lat)));

        String sql = "SELECT stop_id, stop_name, stop_latitude, stop_longitude "
                + "FROM stop_information "
                + "WHERE stop_latitude BETWEEN ? AND ? "
                + "  AND stop_longitude BETWEEN ? AND ?";

        List<NearbyStop> tmp = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerstop.lat - dLat);
            ps.setDouble(idx++, centerstop.lat + dLat);
            ps.setDouble(idx++, centerstop.lon - dLon);
            ps.setDouble(idx++, centerstop.lon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt("stop_id");
                    String name = rs.getString("stop_name");
                    double lat = rs.getDouble("stop_latitude");
                    double lon = rs.getDouble("stop_longitude");

                    int meters = (int) Math.round(distanceMeters(centerstop.lat, centerstop.lon, lat, lon));
                    if (meters <= radiusM) {
                        tmp.add(new NearbyStop(sid, name, meters, lat, lon));
                    }
                }
            }
        }

        // 近い順
        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));

        if (tmp.isEmpty() || tmp.get(0).stopId != centerStopId) {
            tmp.add(0, new NearbyStop(centerStopId, centerstop.name, 0, centerstop.lat, centerstop.lon));
        }

        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }

    // ある緯度経度の近くの停留所を列挙
    private List<NearbyStop> nearbyStopsByLatLon(Connection conn, double centerLat, double centerLon,
            int radiusM, int limit) throws SQLException {

        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerLat)));

        String sql = "SELECT stop_id, stop_name, stop_latitude, stop_longitude " +
                "FROM stop_information " +
                "WHERE stop_latitude BETWEEN ? AND ? " +
                "  AND stop_longitude BETWEEN ? AND ?";

        List<NearbyStop> tmp = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerLat - dLat);
            ps.setDouble(idx++, centerLat + dLat);
            ps.setDouble(idx++, centerLon - dLon);
            ps.setDouble(idx++, centerLon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt("stop_id");
                    String name = rs.getString("stop_name");
                    double lat = rs.getDouble("stop_latitude");
                    double lon = rs.getDouble("stop_longitude");

                    int meters = (int) Math.round(distanceMeters(centerLat, centerLon, lat, lon));
                    if (meters <= radiusM)
                        tmp.add(new NearbyStop(sid, name, meters, lat, lon));
                }
            }
        }

        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));
        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }

    // ポートを事業者ごとにグループ化
    private java.util.Map<Integer, java.util.List<PortCandidate>> groupPortsByOperator(List<PortCandidate> ports) {
        java.util.Map<Integer, java.util.List<PortCandidate>> portsByOperator = new java.util.HashMap<>();
        for (PortCandidate p : ports) {
            portsByOperator.computeIfAbsent(p.operatorId, k -> new java.util.ArrayList<>()).add(p);
        }
        return portsByOperator;
    }

    // 停留所リスト周辺のポートを収集（重複除去）
    private List<PortCandidate> collectNearbyPortsFromStops(
            Connection conn, List<NearbyStop> stops, int radiusM, int portLimit, boolean needBikes, boolean needDocks)
            throws SQLException {
        java.util.Map<Integer, PortCandidate> portById = new java.util.HashMap<>();
        for (NearbyStop stop : stops) {
            List<PortCandidate> ports = nearbyPorts(conn, stop.lat, stop.lon, radiusM, portLimit, needBikes, needDocks);
            for (PortCandidate p : ports) {
                portById.putIfAbsent(p.portId, p);
            }
        }
        return new ArrayList<>(portById.values());
    }
    // -------------------- 基本経路系 --------------------

    // (1) 徒歩のみの結果
    private static class WalkOnlyPlan {
        final String fromName;
        final String toName;
        final int distanceM;
        final int minutes;
        final String startTime; // "HH:mm"
        final String endTime; // "HH:mm"

        WalkOnlyPlan(String fromName, String toName, int distanceM, int minutes, String startTime, String endTime) {
            this.fromName = fromName;
            this.toName = toName;
            this.distanceM = distanceM;
            this.minutes = minutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (3) 乗換なしの結果
    private static class DirectPlan {
        final WalkPath walk0;
        final DirectPath leg;
        final WalkPath walk2;
        final int totalMinutes;
        final String startTime; // "HH:mm"
        final String endTime; // "HH:mm"

        DirectPlan(WalkPath walk0, DirectPath leg, WalkPath walk2, int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.leg = leg;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (4) 乗換ありの結果
    private static class TransferPath {
        final WalkPath walk0; // 出発地 -> 1本目乗車停留所
        final DirectPath leg1; // 乗り物1
        final WalkPath walk1; // 乗換徒歩
        final DirectPath leg2; // 乗り物2
        final WalkPath walk2; // 最後の徒歩
        final int totalMinutes;
        final String startTime; // baseTime
        final String endTime; // 最終到着(徒歩後)

        TransferPath(WalkPath walk0, DirectPath leg1, WalkPath walk1, DirectPath leg2, WalkPath walk2,
                int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.leg1 = leg1;
            this.walk1 = walk1;
            this.leg2 = leg2;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // 乗り換えが同一地点かどうかを判断する関数
    private boolean isZeroWalk(WalkPath w) {
        if (w == null)
            return true;
        boolean same = (w.fromName != null && w.toName != null && w.fromName.equals(w.toName));
        return same && w.min == 0 && w.dist == 0;
    }

    // 結果を統一して格納するためのクラス
    private static class ResultItem {
        // 0=徒歩のみ, 1=自転車のみ, 2=直通, 3=乗換, 4=直通->自転車, 5=自転車->直通
        final int kind;
        final LocalTime end; // ソートキー
        final int totalMinutes; // タイブレーク
        final String firstRoute; // 乗換だけ tp.leg1.routeName を入れる
        final Object payload; // WalkOnlyPlan / DirectPlan / TransferPath

        ResultItem(int kind, String endTime, int totalMinutes, String firstRoute, Object payload) {
            this.kind = kind;
            this.end = LocalTime.parse(endTime);
            this.totalMinutes = totalMinutes;
            this.firstRoute = firstRoute;
            this.payload = payload;
        }
    }

    // -------------------- 自転車系 --------------------

    // 乗換自転車ポートの候補
    private static class PortCandidate {
        final int portId;
        final int operatorId;
        final String operatorName;
        final String operatorContact;
        final String portName;
        final double lat;
        final double lon;
        final int distance; // centerからの直線距離m

        PortCandidate(int portId, int operatorId, String operatorName, String operatorContact, String portName,
                double lat, double lon, int distance) {
            this.portId = portId;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.operatorContact = operatorContact;
            this.portName = portName;
            this.lat = lat;
            this.lon = lon;
            this.distance = distance;
        }
    }

    // ある緯度経度の近くのポートを列挙
    private List<PortCandidate> nearbyPorts(Connection conn, double centerLat, double centerLon,
            int radiusM, int limit, boolean needBikes, boolean needFreeDocks) throws SQLException {

        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerLat)));

        String sql = "SELECT * FROM port_status " +
            "WHERE port_latitude BETWEEN ? AND ? " +
            "  AND port_longitude BETWEEN ? AND ? ";

        if (needBikes)
            sql += " AND bikes > 0 ";
        if (needFreeDocks)
            sql += " AND free_docks > 0 ";

        List<PortCandidate> tmp = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerLat - dLat);
            ps.setDouble(idx++, centerLat + dLat);
            ps.setDouble(idx++, centerLon - dLon);
            ps.setDouble(idx++, centerLon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                boolean hasOperatorContact = hasColumn(meta, "operator_contact");

                while (rs.next()) {
                    int pid = rs.getInt("port_id");
                    int opid = rs.getInt("operator_id");
                    String opn = rs.getString("operator_name");
                    String pn = rs.getString("port_name");
                    double lat = rs.getDouble("port_latitude");
                    double lon = rs.getDouble("port_longitude");
                    String contact = hasOperatorContact ? rs.getString("operator_contact") : null;

                    int dist = (int) Math.round(distanceMeters(centerLat, centerLon, lat, lon));
                    if (dist <= radiusM) {
                        tmp.add(new PortCandidate(pid, opid, opn, contact, pn, lat, lon, dist));
                    }
                }
            }
        }

        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));
        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }

    // 自転車移動のクラス (出発ポート, 到着ポート, 距離, 分, 出発時間, 到着時間)
    private static class BikePath {
        final int operatorId;
        final String operatorName;
        final String operatorContact;
        final String fromPortName;
        final String toPortName;
        final int distanceM;
        final int rideMinutes;
        final String startTime; // "HH:mm" (解錠後)
        final String endTime; // "HH:mm" (到着)

        BikePath(int operatorId, String operatorName, String operatorContact,
                String fromPortName,
                String toPortName,
                int distanceM, int rideMinutes,
                String startTime, String endTime) {
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.operatorContact = operatorContact;
            this.fromPortName = fromPortName;
            this.toPortName = toPortName;
            this.distanceM = distanceM;
            this.rideMinutes = rideMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (3) 自転車のみの結果
    private static class BikeDirectPlan {
        final WalkPath walk0;
        final BikePath bike;
        final WalkPath walk2;
        final int totalMinutes;
        final String startTime;
        final String endTime;

        BikeDirectPlan(WalkPath walk0, BikePath bike, WalkPath walk2, int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.bike = bike;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (5) 公共交通 → 自転車の結果
    private static class TransferTransitBike {
        final WalkPath walk0;
        final DirectPath leg1;
        final WalkPath walk1; // 停留所→ポート
        final BikePath bike;
        final WalkPath walk2; // ポート→目的地
        final int totalMinutes;
        final String startTime;
        final String endTime;

        TransferTransitBike(WalkPath walk0, DirectPath leg1, WalkPath walk1, BikePath bike, WalkPath walk2,
                int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.leg1 = leg1;
            this.walk1 = walk1;
            this.bike = bike;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (6) 自転車 → 公共交通の結果
    private static class TransferBikeTransit {
        final WalkPath walk0; // 出発地→ポート
        final BikePath bike;
        final WalkPath walk1; // ポート→停留所
        final DirectPath leg2;
        final WalkPath walk2; // 最後の徒歩
        final int totalMinutes;
        final String startTime;
        final String endTime;

        TransferBikeTransit(WalkPath walk0, BikePath bike, WalkPath walk1, DirectPath leg2, WalkPath walk2,
                int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.bike = bike;
            this.walk1 = walk1;
            this.leg2 = leg2;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // -------------------- 表示系 --------------------

    // route_color を表示用に安全な色(#RRGGBB)に正規化
    // - DBに 'blue' などの色名を入れている場合もここでHEXに変換してOK
    private String safeColor(String raw) {
        if (raw == null)
            return "#9ca3af"; // gray-400
        String v = raw.trim();
        if (v.isEmpty())
            return "#9ca3af";

        // 既にHEX形式ならそのまま
        if (v.matches("^[0-9a-fA-F]{6}$"))
            return "#" + v;
        if (v.matches("^#[0-9a-fA-F]{6}$"))
            return v;

        // 色名（あなたのINSERTに合わせる）
        String key = v.toLowerCase();
        switch (key) {
            case "blue":
                return "#2563eb";
            case "orange":
                return "#f97316";
            case "gray":
            case "grey":
                return "#6b7280";
            case "green":
                return "#16a34a";
            case "red":
                return "#dc2626";
            case "purple":
                return "#7c3aed";
            default:
                return "#9ca3af";
        }
    }

    private boolean hasColumn(java.sql.ResultSetMetaData meta, String columnLabel) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (columnLabel.equalsIgnoreCase(meta.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeContact(String contact, String operatorName) {
        if (contact != null) {
            String trimmed = contact.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        if (operatorName != null && !operatorName.isEmpty()) {
            return operatorName + " (連絡先未登録)";
        }
        return "連絡先未登録";
    }

    // --- 経路表示（HTMLタグ）用ヘルパ ---
    private String tagArrow() {
        return "<span class=\"arrow-mini\">→</span>";
    }
    private String tagWalk(String minutes) {
        return "<span class=\"tag walk\">徒歩 " + esc(minutes) + "</span>";
    }
    private String tagTransfer() {
        return "<span class=\"tag transfer\">乗換</span>";
    }
    private String tagBike(String rideMinutes) {
        // operatorName を出したいならここで表示
        return "<span class=\"tag bike\">シェアサイクル " + esc(rideMinutes) + "</span>";
    }
    private String tagLine(String routeName, String routeColor) {
        String c = safeColor(routeColor);
        return "<span class=\"tag line\" style=\"--line:" + c + "\">" + esc(routeName) + "</span>";
    }

    // --- 各プランの「経路」セル(HTML)生成 ---
    private String pathHtml(WalkOnlyPlan wp) {
        return "<div class=\"path\">" + tagWalk(wp.minutes + "分") + "</div>";
    }

    private String pathHtml(DirectPlan dp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(dp.walk0)) {
            h.append(tagWalk(dp.walk0.min + "分")).append(tagArrow());
        }
        h.append(tagLine(dp.leg.routeName, dp.leg.routeColor));
        if (!isZeroWalk(dp.walk2)) {
            h.append(tagArrow()).append(tagWalk(dp.walk2.min + "分"));
        }
        h.append("</div>");
        return h.toString();
    }

    private String pathHtml(TransferPath tp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(tp.walk0)) h.append(tagWalk(tp.walk0.min + "分")).append(tagArrow());

        h.append(tagLine(tp.leg1.routeName, tp.leg1.routeColor));

        // 乗換（徒歩 or 同一駅乗換）
        h.append(tagArrow());
        if (!isZeroWalk(tp.walk1)) h.append(tagWalk(tp.walk1.min + "分"));
        else h.append(tagTransfer());
        h.append(tagArrow());

        h.append(tagLine(tp.leg2.routeName, tp.leg2.routeColor));

        if (!isZeroWalk(tp.walk2)) h.append(tagArrow()).append(tagWalk(tp.walk2.min + "分"));
        h.append("</div>");
        return h.toString();
    }

    private String pathHtml(BikeDirectPlan bp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(bp.walk0)) h.append(tagWalk(bp.walk0.min + "分")).append(tagArrow());
        h.append(tagBike(bp.bike.rideMinutes + "分"));
        if (!isZeroWalk(bp.walk2)) h.append(tagArrow()).append(tagWalk(bp.walk2.min + "分"));
        h.append("</div>");
        return h.toString();
    }

    private String pathHtml(TransferTransitBike tp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(tp.walk0)) h.append(tagWalk(tp.walk0.min + "分")).append(tagArrow());
        h.append(tagLine(tp.leg1.routeName, tp.leg1.routeColor));
        h.append(tagArrow());

        // 停留所→ポートが徒歩になるので徒歩タグ
        if (!isZeroWalk(tp.walk1)) h.append(tagWalk(tp.walk1.min + "分")).append(tagArrow());
        h.append(tagBike(tp.bike.rideMinutes + "分"));

        // ポート→目的地
        if (!isZeroWalk(tp.walk2)) h.append(tagArrow()).append(tagWalk(tp.walk2.min + "分"));
        h.append("</div>");
        return h.toString();
    }

    private String pathHtml(TransferBikeTransit tp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(tp.walk0)) h.append(tagWalk(tp.walk0.min + "分")).append(tagArrow());
        h.append(tagBike(tp.bike.rideMinutes + "分"));
        h.append(tagArrow());

        // ポート→停留所が徒歩
        if (!isZeroWalk(tp.walk1)) h.append(tagWalk(tp.walk1.min + "分")).append(tagArrow());

        h.append(tagLine(tp.leg2.routeName, tp.leg2.routeColor));
        if (!isZeroWalk(tp.walk2)) h.append(tagArrow()).append(tagWalk(tp.walk2.min + "分"));
        h.append("</div>");
        return h.toString();
    }

    private String chipLine(String routeName, String routeColor) {
        if (routeName == null || routeName.trim().isEmpty())
            return "";
        String c = safeColor(routeColor);
        return "<span class=\"chip line\" style=\"--line:" + c + "\"><span class=\"dot\"></span>"
                + esc(routeName) + "</span>";
    }

    private String chipTrip(String tripName) {
        if (tripName == null || tripName.trim().isEmpty())
            return "";
        return "<span class=\"chip trip\">便名 " + esc(tripName) + "</span>";
    }

    private String chipInfo(String text) {
        if (text == null || text.trim().isEmpty())
            return "";
        return "<span class=\"chip info\">" + esc(text) + "</span>";
    }

    private String chipContact(String contact) {
        if (contact == null || contact.trim().isEmpty())
            return "";
        return "<span class=\"chip contact\">連絡先 " + esc(contact) + "</span>";
    }

    private void printStep(PrintWriter out, String kind, String mainHtml, String metaHtml) {
        out.println("<div class=\"step\">"
                + "<span class=\"kind\">" + esc(kind) + "</span>"
                + "<span class=\"main\">" + mainHtml + "</span>"
                + "<span class=\"meta\">" + metaHtml + "</span>"
                + "</div>");
    }

    private String summaryItem(String label, String value, String sub) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"summary-item\">");
        sb.append("<div class=\"summary-label\">").append(esc(label)).append("</div>");
        sb.append("<div class=\"summary-value\">").append(esc(value)).append("</div>");
        if (sub != null && !sub.trim().isEmpty()) {
            sb.append("<div class=\"summary-sub\">").append(esc(sub)).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    // 徒歩のみ の結果を表示
    private void printWalkOnlyRow(PrintWriter out, WalkOnlyPlan wp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(wp) + "</td>");
        out.println("<td>" + esc(hhmm(wp.startTime)) + " → " + esc(hhmm(wp.endTime)) + "</td>");
        out.println("<td>" + wp.minutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 自転車のみ の結果を表示
    private void printBikeDirectRow(PrintWriter out, BikeDirectPlan bp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(bp) + "</td>");
        out.println("<td>" + esc(hhmm(bp.startTime)) + " → " + esc(hhmm(bp.endTime)) + "</td>");
        out.println("<td>" + bp.totalMinutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }

    // 直通 の結果を表示
    private void printDirectRow(PrintWriter out, DirectPlan dp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(dp) + "</td>");
        out.println("<td>" + esc(hhmm(dp.startTime)) + " → " + esc(hhmm(dp.endTime)) + "</td>");
        out.println("<td>" + dp.totalMinutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }

    // 乗換あり の結果表示
    private void printTransferRow(PrintWriter out, TransferPath tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMinutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 公共交通 -> 自転車 の結果を表示
    private void printTransitBikeRow(PrintWriter out, TransferTransitBike tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMinutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 自転車 -> 公共交通 の結果を表示
    private void printBikeTransitRow(PrintWriter out, TransferBikeTransit tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMinutes + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }


}
