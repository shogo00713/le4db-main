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
            _dbname   = prop.getProperty("dbname");
            _username = prop.getProperty("username");
            _password = prop.getProperty("password");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // メインの関数 (doGet)
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        // フォームでやり取りするパラメータ
        String fromstop        = request.getParameter("from_stop");  // 出発地
        String tostop          = request.getParameter("to_stop");    // 目的地
        String day             = request.getParameter("day");        // 平日 or 休日
        String timemode        = request.getParameter("time_mode");  // 現在 or 指定時間
        String timevalue       = request.getParameter("time_val");   // HH:mm
        String fromstopidstr   = request.getParameter("from_id");   // 出発地候補ID (選択された後)
        String tostopidstr     = request.getParameter("to_id");     // 目的地候補ID (選択された後)
        

        // ---- いじる定数 ----

        // 時間関係定数
        final int TRANSFER_MIN = 2;                // 乗り換えするのに必要な最低時間
        final int BIKE_UNLOCK_MIN = 1;             // 借りる / 解錠 の最低時間
        final int BIKE_LOCK_MIN   = 1;             // 返す   / 施錠 の最低時間

        // 距離関係定数
        final int FROM_RADIUS_M = 1000;            // 出発地周りの徒歩圏最大
        final int TO_RADIUS_M   = 1000;            // 到着地周りの徒歩圏最大
        final int TRANSFER_RADIUS_M = 300;         // 乗換の徒歩圏最大
        final int BIKE_PORT_RADIUS_M = 400;        // 停留所 と ポート間の徒歩圏最大
        final int BIKE_MAX_RIDE_M = 6000;          // 自転車移動の最大距離（暴走防止）

        // 探索関係定数
        final int MID_LIMIT = 30;                  // 乗り換え地点候補の探索数上限
        final int NEAR_LIMIT = 50;                 // 乗換経路探索数上限
        final int PORT_LIMIT = 5;                  // 近隣ポートの探索数上限
        final int RESULT_LIMIT = 5;                // 表示する乗換経路の最大
    
        // 徒歩/自転車速度関係定数
        final double meter_correction = 1.25;      // 徒歩距離補正係数 (直線 -> 道のり)
        final double meter_per_minutes = 80.0;     // 徒歩の速さは 80m/分
        final double bike_meter_correction = 1.5;  // 自転車距離補正係数 (直線 -> 自転車通行可能な道のり)
        final double BIKE_M_PER_MIN = 250.0;       // 自転車の速さは 250m/分

        // -------------------

        // -----------------------------------------------------------------------------------------------

        Integer fromid = null; // Integer 型で null 許容
        Integer toid   = null; // Integer 型で null 許容

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
        if (fromstop == null) fromstop = "";
        if (tostop == null) tostop = "";
        if (day == null || day.isEmpty()) day = "平日";
        if (timemode == null) timemode = "now";
        if (timevalue == null)  timevalue = "";

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
                + "--bg:#f6f7fb;"
                + "--panel:#ffffff;"
                + "--text:#111827;"
                + "--muted:#6b7280;"
                + "--border:#e5e7eb;"
                + "--primary:#2563eb;"
                + "--primary2:#1d4ed8;"
                + "--radius:14px;"
                + "--shadow:0 10px 30px rgba(0,0,0,.08);"
                + "--gap:12px;"
                + "}");
        out.println("*{box-sizing:border-box;}");
        out.println("body{margin:0;background:var(--bg);color:var(--text);"
                + "font-family:system-ui,-apple-system,\"Segoe UI\",Roboto,\"Noto Sans JP\",\"Hiragino Kaku Gothic ProN\",Meiryo,sans-serif;"
                + "}");
        out.println(".app{max-width:920px;margin:28px auto;padding:0 16px;}");
        out.println(".header{margin-bottom:14px;}");
        out.println(".title{font-size:22px;margin:0 0 6px 0;}");
        out.println(".subtitle{margin:0;color:var(--muted);font-size:13px;}");
        out.println(".card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);box-shadow:var(--shadow);padding:16px;}");
        out.println(".form{display:grid;grid-template-columns:1fr 1fr;gap:var(--gap);align-items:end;}");
        out.println(".field{display:flex;flex-direction:column;gap:6px;}");
        out.println(".label{font-size:12px;color:var(--muted);}");
        out.println(".input,.select{width:100%;padding:10px 12px;border:1px solid var(--border);border-radius:10px;"
                + "background:#fff;font-size:14px;outline:none;}");
        out.println(".input:focus,.select:focus{border-color:rgba(37,99,235,.6);box-shadow:0 0 0 4px rgba(37,99,235,.12);}");
        out.println(".actions{grid-column:1/-1;display:flex;gap:10px;align-items:center;}");
        out.println(".btn{appearance:none;border:0;border-radius:10px;padding:10px 14px;font-weight:700;"
                + "background:var(--primary);color:#fff;cursor:pointer;}");
        out.println(".btn:hover{background:var(--primary2);}");
        out.println(".hr{height:1px;background:var(--border);margin:14px 0;}");
        out.println(".alert{padding:10px 12px;border-radius:10px;background:#fff7ed;border:1px solid #fed7aa;color:#9a3412;}");
        out.println(".muted{color:var(--muted);font-size:13px;}");
        out.println(".result-title{font-size:16px;margin:0 0 8px 0;}");
        out.println(".table-wrap{overflow:auto;border:1px solid var(--border);border-radius:12px;}");
        out.println("table{width:100%;border-collapse:collapse;background:#fff;min-width:720px;}");
        out.println("th,td{padding:10px 10px;border-bottom:1px solid var(--border);text-align:left;font-size:14px;white-space:nowrap;}");
        out.println("th{background:#f9fafb;font-size:12px;color:#374151;position:sticky;top:0;}");
        out.println("tr:hover td{background:#fbfdff;}");
        out.println(".cand{grid-column:1/-1;margin-top:10px;}");
        out.println(".cand-list{max-height:220px;overflow:auto;border:1px solid var(--border);border-radius:12px;background:#fff;}");
        out.println(".cand-item{display:flex;gap:10px;align-items:center;padding:10px 12px;border-bottom:1px solid var(--border);}");
        out.println(".cand-item:last-child{border-bottom:none;}");
        out.println(".cand-item:hover{background:#fbfdff;}");
        out.println(".cand-name{font-weight:600;}");
        out.println(".cand-type{color:var(--muted);font-size:12px;}");
        out.println(".detail-row td{background:#fcfcff;}");
        out.println(".legs{display:flex;flex-wrap:wrap;gap:8px;margin:6px 0 0 0;padding:0;list-style:none;}");
        out.println(".leg{border:1px solid var(--border);border-radius:10px;padding:8px 10px;background:#fff;font-size:13px;}");
        out.println(".leg .t{font-weight:700;margin-right:6px;}");
        out.println(".leg .s{color:var(--muted);}");
        out.println(".steps{display:flex;flex-direction:column;gap:8px;margin-top:8px;}");
        out.println(".step{display:grid;grid-template-columns: 52px 1fr auto;gap:10px;padding:10px 12px;border:1px solid var(--border);border-radius:12px;background:#fff;}");
        out.println(".step .kind{font-weight:800;}");
        out.println(".step .main{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}");
        out.println(".step .meta{white-space:nowrap;color:var(--muted);font-size:12px;}");
        out.println("details.summary{margin-top:6px;}");
        out.println("details.summary > summary{cursor:pointer;color:var(--primary);font-weight:700;}");
        out.println("</style>");
        // CSS ここまで

        // HTML本文
        out.println("</head>");
        out.println("<body>");
        out.println("<div class=\"app\">");

        out.println("<div class=\"header\">");
        out.println("<h2 class=\"title\">マルチモーダル路線検索</h2>");
        out.println("<p class=\"subtitle\">出発地/到着地, 運行日, 時刻条件を指定して検索</p>");
        out.println("</div>");

        out.println("<div class=\"card\">");

        // フォーム形式
        out.println("<form class=\"form\" action=\"routesearch\" method=\"GET\">");

        // 出発地 / 目的地 => from_stop / to_stop
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"from_stop\">出発</label>");
        out.println("<input class=\"input\" id=\"from_stop\" type=\"text\" name=\"from_stop\" placeholder=\"例 : 京都駅\" value=\"" + esc(fromstop) + "\"/>");
        out.println("</div>");

        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"to_stop\">到着</label>");
        out.println("<input class=\"input\" id=\"to_stop\" type=\"text\" name=\"to_stop\" placeholder=\"例 : 三条駅\" value=\"" + esc(tostop) + "\"/>");
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
        out.println(option("now",  "現在時刻", timemode));
        out.println(option("spec", "指定時刻", timemode));
        out.println("</select>");
        out.println("</div>");

        // 時刻選択 time_val
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"time_val\">指定時刻（時刻条件=指定時刻のとき）</label>");
        out.println("<input class=\"input\" id=\"time_val\" type=\"time\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");
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
        Connection conn = null;       // 認証 & 接続用
        PreparedStatement ps = null;  // DBに送る文章 
        ResultSet rs = null;          // DBの結果を受け取る文章

        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(
                    "jdbc:postgresql://" + _hostname + ":5432/" + _dbname,
                    _username, _password);

        // -----------------------------------------------------------------------------------------------
        
            // 地点候補 => 1つに選定
            List<StopCandidate> fromCandidates = new ArrayList<>();
            List<StopCandidate> toCandidates   = new ArrayList<>();

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
                fromid = fromCandidates.get(0).stop_id;
            }
            if (toid == null && toCandidates.size() == 1) {
                toid = toCandidates.get(0).stop_id;
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

                    LatLon fixed = getStopById(conn, fromid);
                    if (fixed != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">出発 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixed.name) + "</div>");
                        out.println("</div>");
                    }
                } else {
                    // 選択画面
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"from_id\">出発 (候補)</label>");
                    out.println("<select class=\"select\" id=\"from_id\" name=\"from_id\">");
                    for (StopCandidate c : fromCandidates) {
                        out.println("<option value=\"" + c.stop_id + "\">" + esc(c.stop_name) + "</option>");
                    }
                    out.println("</select>");
                    out.println("</div>");
                }

                // 到着地
                if (toid != null) {
                    out.println("<input type=\"hidden\" name=\"to_id\" value=\"" + toid + "\"/>");

                    LatLon fixed = getStopById(conn, toid);
                    if (fixed != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">到着 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixed.name) + "</div>");
                        out.println("</div>");
                    }
                } else {
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"to_id\">到着 (候補)</label>");
                    out.println("<select class=\"select\" id=\"to_id\" name=\"to_id\">");
                    for (StopCandidate c : toCandidates) {
                        out.println("<option value=\"" + c.stop_id + "\">" + esc(c.stop_name) + "</option>");
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
            
            // 出発地/到着地 を確定 -> その検索に入る
            LatLon fromll = getStopById(conn, fromid);
            LatLon toll   = getStopById(conn, toid);        
            
            // 結果を HTML で表示
            out.println("<h3 class=\"result-title\">結果</h3>");
            out.println("<p class=\"muted\">（指定時刻以降に出発する便から、到着が早い順に表示）</p>");

            // 表を表示するためのフォーマット
            out.println("<div class=\"table-wrap\">");
            out.println("<table>");
            out.println("<tr>"
                    + "<th>路線</th>"
                    + "<th>便</th>"
                    + "<th>出発</th>"
                    + "<th>到着</th>"
                    + "<th>時刻</th>"
                    + "<th>所要時間</th>"
                    + "</tr>");
               
            // 経路探索 (最重要)

            // ---- part 0 (前情報整理) ----

            // 出発地 / 目的地 の近くの停留所を探索
            List<NearbyStop> nearFromStop = nearbyStops(conn, fromid, FROM_RADIUS_M, NEAR_LIMIT);
            List<NearbyStop> nearToStop   = nearbyStops(conn, toid,   TO_RADIUS_M, NEAR_LIMIT);

            // 出発地 / 目的地 の近くのポートを探索
            List<PortCandidate> fromPorts = nearbyPorts(conn, fromll.lat, fromll.lon, FROM_RADIUS_M, PORT_LIMIT, true, false);  // 借りれる自転車がある
            List<PortCandidate> toPorts   = nearbyPorts(conn, toll.lat,   toll.lon,   TO_RADIUS_M,   PORT_LIMIT, false, true);  // 返せるポートが空いている

            // 探索する候補数の上限
            final int TRANSFER_CANDIDATE_LIMIT = RESULT_LIMIT * 30;
            final int DIRECT_CANDIDATE_LIMIT   = RESULT_LIMIT * 30;

            // 結果全体を入れるリスト
            List<ResultItem> results = new ArrayList<>();
        
            // -------------------------



            // ---- part 1 (徒歩のみ) ----

            double dist = distanceMeters(fromll.lat, fromll.lon, toll.lat, toll.lon);
            int walkOnlyMin = walkingminutes(dist, meter_correction, meter_per_minutes);
            String walkOnlyEnd = addMinutes(baseTime, walkOnlyMin);

            results.add(new ResultItem(
                    0, walkOnlyEnd, walkOnlyMin, "",
                    new WalkOnlyPlan(fromll.name, toll.name, (int)Math.round(dist), walkOnlyMin, baseTime, walkOnlyEnd)
            ));

            // -------------------------

            

            // ---- part 2 (自転車のみ) ----

            java.util.Map<String, BikeDirectPlan> bestBike = new java.util.HashMap<>();

            for (PortCandidate fromp : fromPorts) {

                int walk0Min = walkingminutes(fromp.distance, meter_correction, meter_per_minutes);
                WalkPath w0 = new WalkPath(fromll.name, fromp.portName, fromp.distance, walk0Min);

                String t0 = addMinutes(baseTime, walk0Min);
                String bikeStart_M = addMinutes(t0, BIKE_UNLOCK_MIN);

                for (PortCandidate top : toPorts) {
                    if (fromp.operatorId != top.operatorId) continue;
                    if (fromp.portId == top.portId) continue;

                    int rideDist = (int)Math.round(distanceMeters(fromp.lat, fromp.lon, top.lat, top.lon));
                    if (rideDist > BIKE_MAX_RIDE_M) continue;

                    int rideMin = cyclingminutes(rideDist, bike_meter_correction, BIKE_M_PER_MIN);
                    String bikeEnd = addMinutes(bikeStart_M, rideMin);
                    String afterDock = addMinutes(bikeEnd, BIKE_LOCK_MIN);

                    int walk2Dist = (int)Math.round(distanceMeters(top.lat, top.lon, toll.lat, toll.lon));
                    int walk2Min = walkingminutes(walk2Dist, meter_correction, meter_per_minutes);
                    WalkPath w2 = new WalkPath(top.portName, toll.name, walk2Dist, walk2Min);

                    String endTime = addMinutes(afterDock, walk2Min);
                    int totalMin = minutesBetween(baseTime, endTime);

                    BikeLeg bike = new BikeLeg(fromp.operatorId, fromp.operatorName,
                            fromp.portId, fromp.portName, top.portId, top.portName,
                            rideDist, rideMin, bikeStart_M, bikeEnd);

                    BikeDirectPlan plan = new BikeDirectPlan(w0, bike, w2, totalMin, baseTime, endTime);

                    String key = fromp.operatorId + ":" + fromp.portId + "->" + top.portId;
                    BikeDirectPlan cur = bestBike.get(key);
                    if (cur == null || LocalTime.parse(plan.endTime).isBefore(LocalTime.parse(cur.endTime))) {
                        bestBike.put(key, plan);
                    }
                }
            }

            for (BikeDirectPlan p : bestBike.values()) {
                results.add(new ResultItem(1, p.endTime, p.totalMinutes, "", p));
            }

            // ----------------------------



            // ---- part 3 (公共交通 直通) ----

            java.util.Map<Integer, DirectPlan> bestDirectByTrip = new java.util.HashMap<>();

            for (NearbyStop nsfrom : nearFromStop) {
                int walk0Min = walkingminutes(nsfrom.distance, meter_correction, meter_per_minutes);
                String walk0End = addMinutes(baseTime, walk0Min);
                WalkPath w0 = new WalkPath(fromll.name, nsfrom.name, nsfrom.distance, walk0Min);

                String base1 = walk0End;

                for (NearbyStop nsto : nearToStop) {
                    List<DirectPath> dlist = searchDirect(conn, nsfrom.stopId, nsto.stopId, base1, day, 1);
                    if (dlist.isEmpty()) continue;
                    DirectPath leg = dlist.get(0);

                    int walk2Min = walkingminutes(nsto.distance, meter_correction, meter_per_minutes);
                    String walk2End = addMinutes(leg.arrTime, walk2Min);
                    WalkPath w2 = new WalkPath(nsto.name, toll.name, nsto.distance, walk2Min);

                    int totalMin = (int) java.time.Duration.between(
                            java.time.LocalTime.parse(baseTime),
                            java.time.LocalTime.parse(walk2End)
                    ).toMinutes();

                    DirectPlan dp = new DirectPlan(w0, leg, w2, totalMin, baseTime, walk2End);

                    // ★同じ便(trip_id)なら、最良の1個だけ残す
                    DirectPlan cur = bestDirectByTrip.get(leg.tripId);
                    if (cur == null || betterDirect(dp, cur)) {
                        bestDirectByTrip.put(leg.tripId, dp);
                    }

                    // （任意）増えすぎたら早いものだけ残す
                    if (bestDirectByTrip.size() > DIRECT_CANDIDATE_LIMIT) {
                        java.util.List<DirectPlan> tmp = new java.util.ArrayList<>(bestDirectByTrip.values());
                        tmp.sort(java.util.Comparator.comparing(p -> LocalTime.parse(p.endTime)));
                        tmp.subList(DIRECT_CANDIDATE_LIMIT, tmp.size()).clear();
                        bestDirectByTrip.clear();
                        for (DirectPlan p : tmp) bestDirectByTrip.put(p.leg.tripId, p);
                    }
                }
            }

            // results へ追加
            List<DirectPlan> directPlans = new ArrayList<>(bestDirectByTrip.values());
            directPlans.sort(Comparator.comparing(p -> LocalTime.parse(p.endTime)));
            for (DirectPlan dp : directPlans) {
                results.add(new ResultItem(1, dp.endTime, dp.totalMinutes, "", dp));
            }

            // ----------------------------



            // ---- part 4 (公共交通 乗換1回) ----

            // 2) 乗換(2本) ＝ 既存のロジックを「候補多めに集める」＆「nstoは全部見て最良を選ぶ」に調整
            java.util.Set<String> seenTransfer = new java.util.HashSet<>();
            List<TransferPath> transferCandidates = new ArrayList<>();

            outer:
            for (NearbyStop nsfrom : nearFromStop) {
                int walk0Min = walkingminutes(nsfrom.distance, meter_correction, meter_per_minutes);
                String walk0End2 = addMinutes(baseTime, walk0Min);
                WalkPath w0 = new WalkPath(fromll.name, nsfrom.name, nsfrom.distance, walk0Min);

                String base1 = walk0End2;
                List<GoingOption> mids = listTransferCandidates(conn, nsfrom.stopId, base1, day, MID_LIMIT);

                for (GoingOption mid : mids) {
                    List<DirectPath> leg1list = searchDirect(conn, nsfrom.stopId, mid.midStopId, base1, day, 1);
                    if (leg1list.isEmpty()) continue;
                    DirectPath leg1 = leg1list.get(0);

                    List<NearbyStop> nearMidStop = nearbyStops(conn, mid.midStopId, TRANSFER_RADIUS_M, NEAR_LIMIT);
                    for (NearbyStop nsmid : nearMidStop) {

                        int walk1Min = walkingminutes(nsmid.distance, meter_correction, meter_per_minutes);
                        WalkPath w1 = new WalkPath(mid.midStopName, nsmid.name, nsmid.distance, walk1Min);

                        String base2 = addMinutes(leg1.arrTime, TRANSFER_MIN + walk1Min);

                        TransferPath best = null;

                        for (NearbyStop nsto : nearToStop) {
                            int walk2Min = walkingminutes(nsto.distance, meter_correction, meter_per_minutes);

                            List<DirectPath> leg2list = searchDirect(conn, nsmid.stopId, nsto.stopId, base2, day, 1);
                            if (leg2list.isEmpty()) continue;
                            DirectPath leg2 = leg2list.get(0);

                            String walk2End = addMinutes(leg2.arrTime, walk2Min);
                            WalkPath w2 = new WalkPath(nsto.name, toll.name, nsto.distance, walk2Min);

                            int totalMin = (int) java.time.Duration.between(
                                    java.time.LocalTime.parse(baseTime),
                                    java.time.LocalTime.parse(walk2End)
                            ).toMinutes();

                            String key = leg1.tripId + ":" + leg1.fromStopId + ":" + leg1.toStopId
                                    + "|" + leg2.tripId + ":" + leg2.fromStopId + ":" + leg2.toStopId;

                            if (!seenTransfer.add(key)) continue;

                            TransferPath cand = new TransferPath(w0, leg1, w1, leg2, w2, totalMin, baseTime, walk2End);
                            if (best == null || LocalTime.parse(cand.endTime).isBefore(LocalTime.parse(best.endTime))) {
                                best = cand;
                            }
                        }

                        if (best != null) {
                            if (best.leg1.routeName != null && best.leg1.routeName.equals(best.leg2.routeName)) {
                                continue;
                            }
                            transferCandidates.add(best);
                            if (transferCandidates.size() >= TRANSFER_CANDIDATE_LIMIT) break outer;
                        }
                    }
                }
            }

            // 乗換候補も results に入れる（firstRoute はここで持たせる）
            for (TransferPath tp : transferCandidates) {
                results.add(new ResultItem(2, tp.endTime, tp.totalMinutes, tp.leg1.routeName, tp));
            }

            java.util.Set<String> seenTB = new java.util.HashSet<>();

            // 目的地側の「返却可能ポート」を事業者ごとにまとめると速い
            java.util.Map<Integer, java.util.List<PortCandidate>> toPortsByOp = new java.util.HashMap<>();
            for (PortCandidate p : toPorts) {
                toPortsByOp.computeIfAbsent(p.operatorId, k -> new java.util.ArrayList<>()).add(p);
            }

            int addedTB = 0;

            outerTB:
            for (NearbyStop nsfrom : nearFromStop) {
                int walk0Min = walkingminutes(nsfrom.distance, meter_correction, meter_per_minutes);
                WalkPath w0 = new WalkPath(fromll.name, nsfrom.name, nsfrom.distance, walk0Min);
                String base1 = addMinutes(baseTime, walk0Min);

                List<GoingOption> mids = listTransferCandidates(conn, nsfrom.stopId, base1, day, MID_LIMIT);

                for (GoingOption mid : mids) {
                    List<DirectPath> leg1list = searchDirect(conn, nsfrom.stopId, mid.midStopId, base1, day, 1);
                    if (leg1list.isEmpty()) continue;
                    DirectPath leg1 = leg1list.get(0);

                    LatLon midLL = getStopById(conn, mid.midStopId);
                    if (midLL == null) continue;

                    // 乗換停留所の近くで「借りれるポート」
                    List<PortCandidate> startPorts = nearbyPorts(conn, midLL.lat, midLL.lon, BIKE_PORT_RADIUS_M, PORT_LIMIT, true, false);

                    for (PortCandidate pStart : startPorts) {
                        java.util.List<PortCandidate> destList = toPortsByOp.get(pStart.operatorId);
                        if (destList == null) continue;

                        int walk1Dist = (int)Math.round(distanceMeters(midLL.lat, midLL.lon, pStart.lat, pStart.lon));
                        int walk1Min = walkingminutes(walk1Dist, meter_correction, meter_per_minutes);
                        WalkPath w1 = new WalkPath(mid.midStopName, pStart.portName, walk1Dist, walk1Min);

                        String bikeStart = addMinutes(addMinutes(leg1.arrTime, TRANSFER_MIN + walk1Min), BIKE_UNLOCK_MIN);

                        for (PortCandidate pEnd : destList) {
                            if (pStart.portId == pEnd.portId) continue;

                            int rideDist = (int)Math.round(distanceMeters(pStart.lat, pStart.lon, pEnd.lat, pEnd.lon));
                            if (rideDist > BIKE_MAX_RIDE_M) continue;

                            int rideMin = cyclingminutes(rideDist, bike_meter_correction, BIKE_M_PER_MIN);
                            String bikeEnd = addMinutes(bikeStart, rideMin);
                            String afterDock = addMinutes(bikeEnd, BIKE_LOCK_MIN);

                            int walk2Dist = (int)Math.round(distanceMeters(pEnd.lat, pEnd.lon, toll.lat, toll.lon));
                            int walk2Min = walkingminutes(walk2Dist, meter_correction, meter_per_minutes);
                            WalkPath w2 = new WalkPath(pEnd.portName, toll.name, walk2Dist, walk2Min);

                            String endTime = addMinutes(afterDock, walk2Min);
                            int totalMin = minutesBetween(baseTime, endTime);

                            String key = "TB:" + leg1.tripId + "|" + pStart.operatorId + ":" + pStart.portId + "->" + pEnd.portId;
                            if (!seenTB.add(key)) continue;

                            BikeLeg bike = new BikeLeg(pStart.operatorId, pStart.operatorName,
                                    pStart.portId, pStart.portName, pEnd.portId, pEnd.portName,
                                    rideDist, rideMin, bikeStart, bikeEnd);

                            TransferTransitBike plan = new TransferTransitBike(w0, leg1, w1, bike, w2, totalMin, baseTime, endTime);

                            // firstRoute は「1本目(公共交通)」でOK（既存の重複抑制と相性が良い）
                            results.add(new ResultItem(2, endTime, totalMin, leg1.routeName, plan));

                            if (++addedTB >= TRANSFER_CANDIDATE_LIMIT) break outerTB;
                        }
                    }
                }
            }

            java.util.Set<String> seenBT = new java.util.HashSet<>();

            // 中間で返却できるポート（出発地の近くを広めに）
            List<PortCandidate> midPorts = nearbyPorts(conn, fromll.lat, fromll.lon, BIKE_MAX_RIDE_M, 30, false, true);

            int addedBT = 0;

            outerBT:
            for (PortCandidate pStart : fromPorts) { // 借りれる
                int walk0Min = walkingminutes(pStart.distance, meter_correction, meter_per_minutes);
                WalkPath w0 = new WalkPath(fromll.name, pStart.portName, pStart.distance, walk0Min);

                String t0 = addMinutes(baseTime, walk0Min);
                String bikeStart = addMinutes(t0, BIKE_UNLOCK_MIN);

                for (PortCandidate pEnd : midPorts) { // 返却できる
                    if (pStart.operatorId != pEnd.operatorId) continue;
                    if (pStart.portId == pEnd.portId) continue;

                    int rideDist = (int)Math.round(distanceMeters(pStart.lat, pStart.lon, pEnd.lat, pEnd.lon));
                    if (rideDist > BIKE_MAX_RIDE_M) continue;

                    int rideMin = cyclingminutes(rideDist, bike_meter_correction, BIKE_M_PER_MIN);
                    String bikeEnd = addMinutes(bikeStart, rideMin);
                    String afterDock = addMinutes(bikeEnd, BIKE_LOCK_MIN);

                    // 返却ポートの近くの停留所から公共交通へ
                    List<NearbyStop> boardStops = nearbyStopsByLatLon(conn, pEnd.lat, pEnd.lon, TRANSFER_RADIUS_M, 10);

                    for (NearbyStop board : boardStops) {
                        int walk1Min = walkingminutes(board.distance, meter_correction, meter_per_minutes);
                        WalkPath w1 = new WalkPath(pEnd.portName, board.name, board.distance, walk1Min);
                        String base2 = addMinutes(afterDock, TRANSFER_MIN + walk1Min);

                        // board -> nearToStop の直通を探して最良を1個
                        DirectPath bestLeg2 = null;
                        WalkPath bestWalk2 = null;
                        String bestEnd = null;
                        int bestTotal = Integer.MAX_VALUE;

                        for (NearbyStop nsto : nearToStop) {
                            List<DirectPath> leg2list = searchDirect(conn, board.stopId, nsto.stopId, base2, day, 1);
                            if (leg2list.isEmpty()) continue;
                            DirectPath leg2 = leg2list.get(0);

                            int walk2Min = walkingminutes(nsto.distance, meter_correction, meter_per_minutes);
                            WalkPath w2 = new WalkPath(nsto.name, toll.name, nsto.distance, walk2Min);

                            String endTime = addMinutes(leg2.arrTime, walk2Min);
                            int totalMin = minutesBetween(baseTime, endTime);

                            if (bestEnd == null || LocalTime.parse(endTime).isBefore(LocalTime.parse(bestEnd))) {
                                bestEnd = endTime;
                                bestTotal = totalMin;
                                bestLeg2 = leg2;
                                bestWalk2 = w2;
                            }
                        }

                        if (bestLeg2 == null) continue;

                        String key = "BT:" + pStart.operatorId + ":" + pStart.portId + "->" + pEnd.portId + "|" + bestLeg2.tripId;
                        if (!seenBT.add(key)) continue;

                        BikeLeg bike = new BikeLeg(pStart.operatorId, pStart.operatorName,
                                pStart.portId, pStart.portName, pEnd.portId, pEnd.portName,
                                rideDist, rideMin, bikeStart, bikeEnd);

                        TransferBikeTransit plan = new TransferBikeTransit(w0, bike, w1, bestLeg2, bestWalk2, bestTotal, baseTime, bestEnd);

                        // firstRoute は「公共交通側の路線名」にすると結果がバラけて見やすい
                        results.add(new ResultItem(2, bestEnd, bestTotal, bestLeg2.routeName, plan));

                        if (++addedBT >= TRANSFER_CANDIDATE_LIMIT) break outerBT;
                    }
                }
            }



    

            // 3) 最終ソート（到着が早い順。タイは所要時間→徒歩/直通を少し優先）
            results.sort(
                    Comparator.comparing((ResultItem r) -> r.end)
                            .thenComparingInt(r -> r.totalMinutes)
                            .thenComparingInt(r -> r.kind)
            );

            // 4) 表示：乗換は「1本目の路線(routeName)が同じなら高々1つ」
            java.util.Set<String> usedFirstRoute = new java.util.HashSet<>();
            int shown = 0;

            for (ResultItem ri : results) {
                if (shown >= RESULT_LIMIT) break;

                if (ri.kind == 2) {
                    // 乗換のみ適用
                    if (ri.firstRoute != null && !ri.firstRoute.isEmpty()) {
                        if (!usedFirstRoute.add(ri.firstRoute)) {
                            continue; // 1本目が同じ路線はスキップ
                        }
                    }
                }

                if (ri.payload instanceof WalkOnlyPlan) {
                    printWalkOnlyRow(out, (WalkOnlyPlan) ri.payload);

                } else if (ri.payload instanceof DirectPlan) {
                    printDirectRow(out, (DirectPlan) ri.payload);

                } else if (ri.payload instanceof BikeDirectPlan) {
                    printBikeDirectRow(out, (BikeDirectPlan) ri.payload);

                } else if (ri.payload instanceof TransferTransitBike) {
                    printTransitBikeRow(out, (TransferTransitBike) ri.payload);

                } else if (ri.payload instanceof TransferBikeTransit) {
                    printBikeTransitRow(out, (TransferBikeTransit) ri.payload);

                } else {
                    printTransferRow(out, (TransferPath) ri.payload); // 既存の公共→公共
                }
                shown++;
            }

            // -----------------------------------------------------------------------------------------------            

            
            // 結果まとめ
            out.println("</table>");
            out.println("</div>"); // table-wrap

            out.println("<br/>");

        // 例外処理 (DB関係)
        } catch (Exception e) {
            out.println("<pre>エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException e) {}
            try { if (ps != null) ps.close(); } catch (SQLException e) {}
            try { if (conn != null) conn.close(); } catch (SQLException e) {}
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


    // --------------------- 便利関数系 -------------------

    // 文字エラー対策1
    private String esc(String s) {
        if (s == null) return "";
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
    private String hhmm(String t){
        if (t == null) return "";
        return (t.length() >= 5) ? t.substring(0, 5) : t;
    }

    private int minutesBetween(String startHHmm, String endHHmm) {
        LocalTime s = LocalTime.parse(startHHmm);
        LocalTime e = LocalTime.parse(endHHmm);
        long m = java.time.Duration.between(s, e).toMinutes();
        if (m < 0) m += 24 * 60;
        return (int)m;
    }

    private int cyclingminutes(int meters, double bike_meter_correction, double BIKE_M_PER_MIN) {
        if (meters <= 0) return 0;
    	int minutes = (int) Math.ceil( meters * bike_meter_correction / BIKE_M_PER_MIN);
        return Math.max(0,minutes);
    }


    // --------------------- 候補検索系 --------------------

    // 緯度経度を渡すためのクラス
    private static class LatLon {
    	final double lat;
    	final double lon;
    	final String name;
    	
    	LatLon(double lat, double lon, String name) {
    		this.lat = lat;
    		this.lon = lon;
    		this.name = name;

    	}
    }
   
    // getLatLon の stop_id 版
    private LatLon getStopById (Connection conn, int stop_id) throws SQLException {
    	String sql =
    			"SELECT stop_id, stop_name, stop_latitude, stop_longitude "
    		  + "FROM stop_information "
    		  + "WHERE stop_id = ? "
    		  + "LIMIT 1";
    	
    	try (PreparedStatement ps = conn.prepareStatement(sql)) {
    		int idx = 1;
    		ps.setInt(idx++, stop_id);
    		
    		try (ResultSet rs = ps.executeQuery()) {
    			if(!rs.next()) return null;
    			
    			String name = rs.getString("stop_name");
    			double lat = rs.getDouble("stop_latitude");
    			double lon = rs.getDouble("stop_longitude");
    			return new LatLon (lat, lon, name);
    		}
    	}
    }
    
    // 緯度経度から距離(メートル)を計算
    private double distanceMeters (double lat1, double lon1, double lat2, double lon2) {
    	double R = 6371000.0;
    	double diflat = Math.toRadians(lat2 - lat1);
    	double diflon = Math.toRadians(lon2 - lon1);
    	double a      = Math.sin(diflat / 2) * Math.sin (diflat / 2) 
    			      + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(diflon / 2) * Math.sin(diflon / 2);
    	double c      = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    	return R * c;
    }
    
    // 距離から徒歩時間を算出
    private int walkingminutes(double r, double meter_correction, double meter_per_minutes) {
    	int minutes = (int) Math.ceil( r * meter_correction / meter_per_minutes);
        return Math.max(0,	 minutes);
    }
    
    // 出発地/目的地 の候補
    private static class StopCandidate {
    	final int stop_id;
    	final String stop_name;
    	
    	StopCandidate(int stop_id, String stop_name) {
    		this.stop_id = stop_id;
    		this.stop_name = stop_name;
    	}
    }
    
    // 候補の前検索
    private List<StopCandidate> searchStopCandidates (Connection conn, String keyword, int limit) throws SQLException {
    	String sql = 
    			  "SELECT stop_id, stop_name, stop_type "
    		    + "FROM stop_information "
    			+ "WHERE stop_name ILIKE ? "
    			+ "ORDER BY CASE "
    			+ "WHEN stop_name = ? THEN 0 "
    			+ "WHEN stop_name ILIKE ? THEN 1 "
    			+ "ELSE 2 END, "
    			+ "CHAR_LENGTH(stop_name) ASC "
    			+ "LIMIT ? ";

    	List<StopCandidate> list = new ArrayList<>();
    	
    	try (PreparedStatement ps = conn.prepareStatement(sql)) {
    		int idx = 1;
    		ps.setString(idx++, "%" + keyword + "%"); // 部分一致
    		ps.setString(idx++, keyword);             // 完全一致
    		ps.setString(idx++, keyword + "%");       // 前方一致
    		ps.setInt(idx++, limit);
    		
    	    try (ResultSet rs = ps.executeQuery()) {
    	        while (rs.next()) {
    	          int stopId = rs.getInt("stop_id");
    	          String name = rs.getString("stop_name");
    	          list.add(new StopCandidate(stopId, name));
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
        final String tripName;
        final int fromStopId;
        final String fromStopName;
        final String depTime; // "HH:mm"
        final int toStopId;
        final String toStopName;
        final String arrTime; // "HH:mm"

        DirectPath (int tripId, String routeName, String tripName,
                   int fromStopId, String fromStopName, String depTime,
                   int toStopId, String toStopName, String arrTime) {
            this.tripId = tripId;
            this.routeName = routeName;
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
                        rs.getString("trip_name"),
                        rs.getInt("from_stop_id"),
                        rs.getString("from_stop_name"),
                        hhmm(rs.getString("dep_time")),
                        rs.getInt("to_stop_id"),
                        rs.getString("to_stop_name"),
                        hhmm(rs.getString("arr_time"))
                    ));
                }
            }
        }
        return list;
    }

    // 直通の徒歩検索
    private static class WalkPath {
        final String fromName;
        final String toName;
        final int distanceM;
        final int minutes;

        WalkPath(String fromName, String toName, int distanceM, int minutes) {
            this.fromName = fromName;
            this.toName = toName;
            this.distanceM = distanceM;
            this.minutes = minutes;
        }
    }

    // 乗換一回の前半の移動
    private static class GoingOption {
        final int midStopId;
        final String midStopName;

        GoingOption(int midStopId, String midStopName) {
            this.midStopId = midStopId;
            this.midStopName = midStopName;
        }
    }

    // 出発地からいける停留所を全部調べる
    private List<GoingOption> listTransferCandidates(
            Connection conn, int fromStopId, String baseTime, String day, int limit
    ) throws SQLException {

        String sql = ""
            + "SELECT DISTINCT "
            + "  t.trip_id AS trip_id, "
            + "  sa_to.stop_id AS mid_stop_id, "
            + "  st.stop_name AS mid_stop_name, "
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

        List<GoingOption> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setString(idx++, baseTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new GoingOption(
                        rs.getInt("mid_stop_id"),
                        rs.getString("mid_stop_name")
                    ));
                }
            }
        }
        return list;
    }

    // より良い乗換か調べる
    private boolean betterDirect(DirectPlan a, DirectPlan b) {
        LocalTime ea = LocalTime.parse(a.endTime);
        LocalTime eb = LocalTime.parse(b.endTime);

        if (ea.isBefore(eb)) return true;
        if (ea.isAfter(eb))  return false;

        // 同着なら「最後の徒歩が短い方」を優先（＝手前下車のゴミを落とす）
        int wa = (a.walk2 == null) ? 0 : a.walk2.distanceM;
        int wb = (b.walk2 == null) ? 0 : b.walk2.distanceM;
        if (wa != wb) return wa < wb;

        // さらに同じなら所要時間が短い方
        return a.totalMinutes < b.totalMinutes;
    }

    // 停留所感のID,距離,時間を持つ
    private static class NearbyStop {
        final int stopId;
        final String name;
        final int distance;

        NearbyStop(int stopId, String name, int distance) {
        	this.stopId = stopId;
            this.name = name;
            this.distance = distance;
        }
    }

    // 近くの停留所のうち近いものを全列挙
    private List<NearbyStop> nearbyStops(Connection conn, int centerStopId, int radiusM, int limit) throws SQLException {
        LatLon centerstop = getStopById(conn, centerStopId);
        if (centerstop == null) return new ArrayList<>();

        // 半径radiusMを緯度経度の範囲に雑に変換（高速化）
        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerstop.lat)));

        String sql =
            "SELECT stop_id, stop_name, stop_latitude, stop_longitude "
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

              int meters = (int)Math.round(distanceMeters(centerstop.lat, centerstop.lon, lat, lon));
              if (meters <= radiusM) {
                tmp.add(new NearbyStop(sid, name, meters));
              }
            }
          }
        }

        // 近い順
        tmp.sort((a,b) -> Integer.compare(a.distance, b.distance));

        if (tmp.isEmpty() || tmp.get(0).stopId != centerStopId) {
          tmp.add(0, new NearbyStop(centerStopId, centerstop.name, 0));
        }

        if (tmp.size() > limit) return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
      }
    
    // --------------------

    // (1) 徒歩のみの結果
    private static class WalkOnlyPlan {
        final String fromName;
        final String toName;
        final int distanceM;
        final int minutes;
        final String startTime; // "HH:mm"
        final String endTime;   // "HH:mm"

        WalkOnlyPlan(String fromName, String toName, int distanceM, int minutes, String startTime, String endTime) {
            this.fromName = fromName;
            this.toName = toName;
            this.distanceM = distanceM;
            this.minutes = minutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (2) 乗換なしの結果
    private static class DirectPlan {
        final WalkPath walk0;
        final DirectPath leg;
        final WalkPath walk2;
        final int totalMinutes;
        final String startTime; // "HH:mm"
        final String endTime;   // "HH:mm"

        DirectPlan(WalkPath walk0, DirectPath leg, WalkPath walk2, int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.leg = leg;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // (3) 乗換ありの結果
    private static class TransferPath {
        final WalkPath walk0;    // 出発地 -> 1本目乗車停留所
        final DirectPath leg1;  // 乗り物1
        final WalkPath walk1;    // 乗換徒歩
        final DirectPath leg2;  // 乗り物2
        final WalkPath walk2;    // 最後の徒歩
        final int totalMinutes;
        final String startTime; // baseTime
        final String endTime;   // 最終到着(徒歩後)

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
    if (w == null) return true;
    boolean same = (w.fromName != null && w.toName != null && w.fromName.equals(w.toName));
    return same && w.minutes == 0 && w.distanceM == 0;
    }

    // 結果のソート用
    private static class ResultItem {
        // 0=徒歩のみ, 1=直通, 2=乗換(2本)
        final int kind;
        final LocalTime end;        // ソートキー
        final int totalMinutes;     // タイブレーク
        final String firstRoute;    // 乗換だけ tp.leg1.routeName を入れる
        final Object payload;       // WalkOnlyPlan / DirectPlan / TransferPath

        ResultItem(int kind, String endTime, int totalMinutes, String firstRoute, Object payload) {
            this.kind = kind;
            this.end = LocalTime.parse(endTime);
            this.totalMinutes = totalMinutes;
            this.firstRoute = firstRoute;
            this.payload = payload;
        }
    }

    // --------------------

    // HTML表示の簡略化
    private void printStep(PrintWriter out, String kind, String main, String meta) {
    out.println("<div class=\"step\">"
        + "<span class=\"kind\">" + esc(kind) + "</span>"
        + "<span class=\"main\">" + esc(main) + "</span>"
        + "<span class=\"meta\">" + esc(meta) + "</span>"
        + "</div>");
    }

    // 徒歩のみの結果を表示
    private void printWalkOnlyRow(PrintWriter out, WalkOnlyPlan wp) {
        out.println("<tr>");
        out.println("<td>徒歩のみ</td>");
        out.println("<td>約" + wp.distanceM + "m</td>");
        out.println("<td>" + esc(wp.fromName) + "</td>");
        out.println("<td>" + esc(wp.toName) + "</td>");
        out.println("<td>" + esc(hhmm(wp.startTime)) + " → " + esc(hhmm(wp.endTime)) + "</td>");
        out.println("<td>" + wp.minutes + "分</td>");
        out.println("</tr>");

        out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
        out.println("<details class=\"summary\">");
        out.println("<summary>経路詳細</summary>");
        out.println("<div class=\"steps\">");
        printStep(out, "徒歩",
                wp.fromName + " → " + wp.toName,
                wp.minutes + "分 / 約" + wp.distanceM + "m, " + hhmm(wp.startTime) + "→" + hhmm(wp.endTime));
        out.println("</div>");
        out.println("</details>");
        out.println("</td></tr>");
    }

    private void printBikeDirectRow(PrintWriter out, BikeDirectPlan bp) {
        StringBuilder route = new StringBuilder();
        if (!isZeroWalk(bp.walk0)) route.append("徒歩 → ");
        route.append("シェアサイクル(").append(bp.bike.operatorName).append(")");
        if (!isZeroWalk(bp.walk2)) route.append(" → 徒歩");

        out.println("<tr>");
        out.println("<td>" + esc(route.toString()) + "</td>");
        out.println("<td>" + esc(bp.bike.fromPortName + " → " + bp.bike.toPortName) + "</td>");
        out.println("<td>" + esc(bp.walk0.fromName) + "</td>");
        out.println("<td>" + esc(bp.walk2.toName) + "</td>");
        out.println("<td>" + esc(hhmm(bp.startTime)) + " → " + esc(hhmm(bp.endTime)) + "</td>");
        out.println("<td>" + bp.totalMinutes + "分</td>");
        out.println("</tr>");

        out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
        out.println("<details class=\"summary\">");
        out.println("<summary>経路詳細</summary>");
        out.println("<div class=\"steps\">");

        if (!isZeroWalk(bp.walk0)) {
            printStep(out, "徒歩",
                    bp.walk0.fromName + " → " + bp.walk0.toName,
                    bp.walk0.minutes + "分 / 約" + bp.walk0.distanceM + "m");
        }

        printStep(out, "自転車",
                bp.bike.fromPortName + " " + hhmm(bp.bike.startTime) + " → " + bp.bike.toPortName + " " + hhmm(bp.bike.endTime),
                "シェアサイクル(" + bp.bike.operatorName + "), " + bp.bike.rideMinutes + "分 / 約" + bp.bike.distanceM + "m");

        if (!isZeroWalk(bp.walk2)) {
            printStep(out, "徒歩",
                    bp.walk2.fromName + " → " + bp.walk2.toName,
                    bp.walk2.minutes + "分 / 約" + bp.walk2.distanceM + "m");
        }

        out.println("</div>");
        out.println("</details>");
        out.println("</td></tr>");
    }

    // 乗換なしの結果を表示
    private void printDirectRow(PrintWriter out, DirectPlan dp) {
    StringBuilder route = new StringBuilder();
    if (!isZeroWalk(dp.walk0)) route.append("徒歩 → ");
    route.append(dp.leg.routeName);
    if (!isZeroWalk(dp.walk2)) route.append(" → 徒歩");

    out.println("<tr>");
    out.println("<td>" + esc(route.toString()) + "</td>");
    out.println("<td>" + esc(dp.leg.tripName) + "</td>");
    out.println("<td>" + esc(dp.walk0.fromName) + "</td>");
    out.println("<td>" + esc(dp.walk2.toName) + "</td>");
    out.println("<td>" + esc(hhmm(dp.startTime)) + " → " + esc(hhmm(dp.endTime)) + "</td>");
    out.println("<td>" + dp.totalMinutes + "分</td>");
    out.println("</tr>");

    out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
    out.println("<details class=\"summary\">");
    out.println("<summary>経路詳細</summary>");
    out.println("<div class=\"steps\">");

    if (!isZeroWalk(dp.walk0)) {
        printStep(out, "徒歩",
                dp.walk0.fromName + " → " + dp.walk0.toName,
                dp.walk0.minutes + "分 / 約" + dp.walk0.distanceM + "m");
    }

    printStep(out, "乗車",
            dp.leg.fromStopName + " " + hhmm(dp.leg.depTime) + " 発 → " + dp.leg.toStopName + " " + hhmm(dp.leg.arrTime) + " 着",
            dp.leg.routeName + " " + dp.leg.tripName);

    if (!isZeroWalk(dp.walk2)) {
        printStep(out, "徒歩",
                dp.walk2.fromName + " → " + dp.walk2.toName,
                dp.walk2.minutes + "分 / 約" + dp.walk2.distanceM + "m");
    }

    out.println("</div>");
    out.println("</details>");
    out.println("</td></tr>");
}

    // 乗換ありの結果表示    
    private void printTransferRow(PrintWriter out, TransferPath tp) {

    // --- 1行目: いままで通りのサマリ行（表の行） ---
    StringBuilder sb = new StringBuilder();
    if (!isZeroWalk(tp.walk0)) sb.append("徒歩 → ");
    sb.append(tp.leg1.routeName);
    if (!isZeroWalk(tp.walk1)) sb.append(" → 徒歩 → ");
    else sb.append(" → 乗換 → ");
    sb.append(tp.leg2.routeName);
    if (!isZeroWalk(tp.walk2)) sb.append(" → 徒歩");
    String route = sb.toString();
    String trips = tp.leg1.tripName + " → " + tp.leg2.tripName;

    out.println("<tr>");
    out.println("<td>" + esc(route) + "</td>");
    out.println("<td>" + esc(trips) + "</td>");
    out.println("<td>" + esc(tp.walk0.fromName) + "</td>");
    out.println("<td>" + esc(tp.walk2.toName) + "</td>");
    out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
    out.println("<td>" + tp.totalMinutes + "分</td>");
    out.println("</tr>");

    // --- 2行目: 詳細行（折りたたみ＋縦リスト） ---
    out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
    out.println("<details class=\"summary\">");
    out.println("<summary>経路詳細</summary>");
    out.println("<div class=\"steps\">");

    // 徒歩0(出発 -> 1本目乗車停留所) は 0m/0分なら消す
    if (!isZeroWalk(tp.walk0)) {
        printStep(out,
            "徒歩",
            tp.walk0.fromName + " → " + tp.walk0.toName,
            tp.walk0.minutes + "分 / 約" + tp.walk0.distanceM + "m");
    }

    // 乗車1
    printStep(out,
        "乗車",
        tp.leg1.fromStopName + " " + hhmm(tp.leg1.depTime) + " 発 → " + tp.leg1.toStopName + " " + hhmm(tp.leg1.arrTime) + " 着",
        tp.leg1.routeName + " " + tp.leg1.tripName);

    // 徒歩1(乗換)
    if (!isZeroWalk(tp.walk1)) {
        printStep(out,
            "徒歩",
            tp.walk1.fromName + " → " + tp.walk1.toName,
            tp.walk1.minutes + "分 / 約" + tp.walk1.distanceM + "m");
    } else {
        // 0分徒歩なら「乗換」として軽く出す（いらなければこの2行ごと消してOK）
        printStep(out, "乗換", "同一駅で乗換", "");
    }

    // 乗車2
    printStep(out,
        "乗車",
        tp.leg2.fromStopName + " " + hhmm(tp.leg2.depTime) + " 発 → " + tp.leg2.toStopName + " " + hhmm(tp.leg2.arrTime) + " 着",
        tp.leg2.routeName + " " + tp.leg2.tripName);

    // 徒歩2(最後)
    if (!isZeroWalk(tp.walk2)) {
        printStep(out,
            "徒歩",
            tp.walk2.fromName + " → " + tp.walk2.toName,
            tp.walk2.minutes + "分 / 約" + tp.walk2.distanceM + "m");
    }

    out.println("</div>");
    out.println("</details>");
    out.println("</td></tr>");
    }

    private void printTransitBikeRow(PrintWriter out, TransferTransitBike tp) {
        String route = "徒歩 → " + tp.leg1.routeName + " → 徒歩 → シェアサイクル(" + tp.bike.operatorName + ") → 徒歩";

        out.println("<tr>");
        out.println("<td>" + esc(route) + "</td>");
        out.println("<td>" + esc(tp.leg1.tripName + " → " + tp.bike.fromPortName + "→" + tp.bike.toPortName) + "</td>");
        out.println("<td>" + esc(tp.walk0.fromName) + "</td>");
        out.println("<td>" + esc(tp.walk2.toName) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMinutes + "分</td>");
        out.println("</tr>");

        out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
        out.println("<details class=\"summary\">");
        out.println("<summary>経路詳細</summary>");
        out.println("<div class=\"steps\">");

        if (!isZeroWalk(tp.walk0)) {
            printStep(out, "徒歩", tp.walk0.fromName + " → " + tp.walk0.toName,
                    tp.walk0.minutes + "分 / 約" + tp.walk0.distanceM + "m");
        }

        printStep(out, "乗車",
                tp.leg1.fromStopName + " " + hhmm(tp.leg1.depTime) + " 発 → " + tp.leg1.toStopName + " " + hhmm(tp.leg1.arrTime) + " 着",
                tp.leg1.routeName + " " + tp.leg1.tripName);

        if (!isZeroWalk(tp.walk1)) {
            printStep(out, "徒歩", tp.walk1.fromName + " → " + tp.walk1.toName,
                    tp.walk1.minutes + "分 / 約" + tp.walk1.distanceM + "m");
        }

        printStep(out, "自転車",
                tp.bike.fromPortName + " " + hhmm(tp.bike.startTime) + " → " + tp.bike.toPortName + " " + hhmm(tp.bike.endTime),
                "シェアサイクル(" + tp.bike.operatorName + "), " + tp.bike.rideMinutes + "分 / 約" + tp.bike.distanceM + "m");

        if (!isZeroWalk(tp.walk2)) {
            printStep(out, "徒歩", tp.walk2.fromName + " → " + tp.walk2.toName,
                    tp.walk2.minutes + "分 / 約" + tp.walk2.distanceM + "m");
        }

        out.println("</div></details></td></tr>");
    }

    private void printBikeTransitRow(PrintWriter out, TransferBikeTransit tp) {
    String route = "徒歩 → シェアサイクル(" + tp.bike.operatorName + ") → 徒歩 → " + tp.leg2.routeName + " → 徒歩";

    out.println("<tr>");
    out.println("<td>" + esc(route) + "</td>");
    out.println("<td>" + esc(tp.bike.fromPortName + "→" + tp.bike.toPortName + " → " + tp.leg2.tripName) + "</td>");
    out.println("<td>" + esc(tp.walk0.fromName) + "</td>");
    out.println("<td>" + esc(tp.walk2.toName) + "</td>");
    out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
    out.println("<td>" + tp.totalMinutes + "分</td>");
    out.println("</tr>");

    out.println("<tr class=\"detail-row\"><td colspan=\"6\">");
    out.println("<details class=\"summary\">");
    out.println("<summary>経路詳細</summary>");
    out.println("<div class=\"steps\">");

    if (!isZeroWalk(tp.walk0)) {
        printStep(out, "徒歩", tp.walk0.fromName + " → " + tp.walk0.toName,
                tp.walk0.minutes + "分 / 約" + tp.walk0.distanceM + "m");
    }

    printStep(out, "自転車",
            tp.bike.fromPortName + " " + hhmm(tp.bike.startTime) + " → " + tp.bike.toPortName + " " + hhmm(tp.bike.endTime),
            "シェアサイクル(" + tp.bike.operatorName + "), " + tp.bike.rideMinutes + "分 / 約" + tp.bike.distanceM + "m");

    if (!isZeroWalk(tp.walk1)) {
        printStep(out, "徒歩", tp.walk1.fromName + " → " + tp.walk1.toName,
                tp.walk1.minutes + "分 / 約" + tp.walk1.distanceM + "m");
    }

    printStep(out, "乗車",
            tp.leg2.fromStopName + " " + hhmm(tp.leg2.depTime) + " 発 → " + tp.leg2.toStopName + " " + hhmm(tp.leg2.arrTime) + " 着",
            tp.leg2.routeName + " " + tp.leg2.tripName);

    if (!isZeroWalk(tp.walk2)) {
        printStep(out, "徒歩", tp.walk2.fromName + " → " + tp.walk2.toName,
                tp.walk2.minutes + "分 / 約" + tp.walk2.distanceM + "m");
    }

    out.println("</div></details></td></tr>");
}


    // -------------------- 自転車系 --------------------

    private static class PortCandidate {
        final int portId;
        final int operatorId;
        final String operatorName;
        final String portName;
        final double lat;
        final double lon;
        final int bikes;
        final int freeDocks;
        final int distance; // centerからの直線距離m

        PortCandidate(int portId, int operatorId, String operatorName, String portName,
                    double lat, double lon, int bikes, int freeDocks, int distance) {
            this.portId = portId;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.portName = portName;
            this.lat = lat;
            this.lon = lon;
            this.bikes = bikes;
            this.freeDocks = freeDocks;
            this.distance = distance;
        }
    }

    private List<PortCandidate> nearbyPorts(Connection conn, double centerLat, double centerLon,
            int radiusM, int limit, boolean needBikes, boolean needFreeDocks) throws SQLException {

        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerLat)));

        String sql =
            "SELECT port_id, operator_id, operator_name, port_name, port_latitude, port_longitude, bikes, free_docks " +
            "FROM port_status " +
            "WHERE port_latitude BETWEEN ? AND ? " +
            "  AND port_longitude BETWEEN ? AND ? ";

        if (needBikes)      sql += " AND bikes > 0 ";
        if (needFreeDocks)  sql += " AND free_docks > 0 ";

        List<PortCandidate> tmp = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setDouble(idx++, centerLat - dLat);
            ps.setDouble(idx++, centerLat + dLat);
            ps.setDouble(idx++, centerLon - dLon);
            ps.setDouble(idx++, centerLon + dLon);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pid = rs.getInt("port_id");
                    int opid = rs.getInt("operator_id");
                    String opn = rs.getString("operator_name");
                    String pn = rs.getString("port_name");
                    double lat = rs.getDouble("port_latitude");
                    double lon = rs.getDouble("port_longitude");
                    int bikes = rs.getInt("bikes");
                    int free = rs.getInt("free_docks");

                    int dist = (int)Math.round(distanceMeters(centerLat, centerLon, lat, lon));
                    if (dist <= radiusM) {
                        tmp.add(new PortCandidate(pid, opid, opn, pn, lat, lon, bikes, free, dist));
                    }
                }
            }
        }

        tmp.sort((a,b) -> Integer.compare(a.distance, b.distance));
        if (tmp.size() > limit) return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }

    private List<NearbyStop> nearbyStopsByLatLon(Connection conn, double centerLat, double centerLon,
        int radiusM, int limit) throws SQLException {

    double dLat = radiusM / 111000.0;
    double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerLat)));

    String sql =
        "SELECT stop_id, stop_name, stop_latitude, stop_longitude " +
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

                int meters = (int)Math.round(distanceMeters(centerLat, centerLon, lat, lon));
                if (meters <= radiusM) tmp.add(new NearbyStop(sid, name, meters));
            }
        }
    }

    tmp.sort((a,b) -> Integer.compare(a.distance, b.distance));
    if (tmp.size() > limit) return new ArrayList<>(tmp.subList(0, limit));
    return tmp;
}

    private static class BikeLeg {
        final int operatorId;
        final String operatorName;
        final int fromPortId;
        final String fromPortName;
        final int toPortId;
        final String toPortName;
        final int distanceM;
        final int rideMinutes;
        final String startTime; // "HH:mm" (解錠後)
        final String endTime;   // "HH:mm" (到着)

        BikeLeg(int operatorId, String operatorName,
                int fromPortId, String fromPortName,
                int toPortId, String toPortName,
                int distanceM, int rideMinutes,
                String startTime, String endTime) {
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.fromPortId = fromPortId;
            this.fromPortName = fromPortName;
            this.toPortId = toPortId;
            this.toPortName = toPortName;
            this.distanceM = distanceM;
            this.rideMinutes = rideMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private static class BikeDirectPlan {
        final WalkPath walk0;
        final BikeLeg bike;
        final WalkPath walk2;
        final int totalMinutes;
        final String startTime;
        final String endTime;

        BikeDirectPlan(WalkPath walk0, BikeLeg bike, WalkPath walk2,
                    int totalMinutes, String startTime, String endTime) {
            this.walk0 = walk0;
            this.bike = bike;
            this.walk2 = walk2;
            this.totalMinutes = totalMinutes;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    // 2レッグ（公共→自転車）
    private static class TransferTransitBike {
        final WalkPath walk0;
        final DirectPath leg1;
        final WalkPath walk1; // 停留所→ポート
        final BikeLeg bike;
        final WalkPath walk2; // ポート→目的地
        final int totalMinutes;
        final String startTime;
        final String endTime;

        TransferTransitBike(WalkPath walk0, DirectPath leg1, WalkPath walk1, BikeLeg bike, WalkPath walk2,
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

    // 2レッグ（自転車→公共）
    private static class TransferBikeTransit {
        final WalkPath walk0; // 出発地→ポート
        final BikeLeg bike;
        final WalkPath walk1; // ポート→停留所
        final DirectPath leg2;
        final WalkPath walk2; // 最後の徒歩
        final int totalMinutes;
        final String startTime;
        final String endTime;

        TransferBikeTransit(WalkPath walk0, BikeLeg bike, WalkPath walk1, DirectPath leg2, WalkPath walk2,
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


}
