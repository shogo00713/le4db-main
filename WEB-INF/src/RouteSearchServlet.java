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
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        // フォームでやり取りするパラメータ
        String fromstop        = request.getParameter("from_stop");  // 出発地
        String tostop          = request.getParameter("to_stop");    // 目的地
        String day             = request.getParameter("day");        // 平日 or 休日
        String timemode        = request.getParameter("time_mode");  // 現在 or 指定時間
        String timevalue       = request.getParameter("time_val");   // HH:mm
        String fromstopidstr   = request.getParameter("from_id");   // 出発地候補ID (選択された場合)
        String tostopidstr     = request.getParameter("to_id");     // 目的地候補ID (選択された場合)
        
        // ---- いじる定数 ----
        final int TRANSFER_MIN = 3;      // 乗換猶予時間 (分)
        final int MID_LIMIT = 30;        // mid候補の探索上限
        final int RESULT_LIMIT = 5;      // 表示する乗換経路の数の最大
        // -------------------

        Integer fromId = null; // Integer 型で null 許容
        Integer toId   = null; // Integer 型で null 許容

        // fromIDStr/toIdStr を fromId/toId (Integer型) に変換
        if (fromstopidstr != null && !fromstopidstr.trim().isEmpty()) {
            try {
                fromId = Integer.valueOf(fromstopidstr);
            } catch (NumberFormatException e) {
                fromId = null;
            }
        }
        if (tostopidstr != null && !tostopidstr.trim().isEmpty()) {
            try {
                toId = Integer.valueOf(tostopidstr);
            } catch (NumberFormatException e) {
                toId = null;
            }
        }
        
        // NULL => 空文字列 に変換
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
        
            // 地点候補 => 一つに選定
            //
            List<StopCandidate> fromCandidates = new ArrayList<>();
            List<StopCandidate> toCandidates   = new ArrayList<>();

            if (fromId == null) {
                fromCandidates = searchStopCandidates(conn, fromstop, 10);
            }
            if (toId == null) {
                toCandidates = searchStopCandidates(conn, tostop, 10);
            }

            // 0件なら終了（HTMLも閉じる）
            if ((fromId == null && fromCandidates.isEmpty()) || (toId == null && toCandidates.isEmpty())) {
                out.println("<p class=\"alert\">出発/到着地点が見つかりませんでした。</p>");
                out.println("</div></div></body></html>"); // card/app/body/html を閉じる
                return;
            }

            // 1件なら自動確定
            if (fromId == null && fromCandidates.size() == 1) {
                fromId = fromCandidates.get(0).stop_id;
            }
            if (toId == null && toCandidates.size() == 1) {
                toId = toCandidates.get(0).stop_id;
            }

            // まだ未確定（=複数候補）なら、候補を選ばせる画面を出す
            if (fromId == null || toId == null) {
                out.println("<div class=\"alert\">候補が複数あります。下から選んでください。</div>");

                out.println("<form class=\"form\" action=\"routesearch\" method=\"GET\">");

                // 元の入力値も引き継ぐ（これがないと条件が消える）(フォームの上部分)
                out.println("<input type=\"hidden\" name=\"from_stop\" value=\"" + esc(fromstop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"to_stop\" value=\"" + esc(tostop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"day\" value=\"" + esc(day) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_mode\" value=\"" + esc(timemode) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");

                // 出発候補
                out.println("<div class=\"field\">");
                out.println("<label class=\"label\" for=\"from_id\">出発（候補）</label>");
                out.println("<select class=\"select\" id=\"from_id\" name=\"from_id\">");
                if (fromId != null) {
                    LatLon fixed = getStopById(conn, fromId); // 既にID確定してる側
                    if (fixed != null) {
                        out.println("<option value=\"" + fixed.stopid + "\" selected>" + esc(fixed.name) + "</option>");
                    }
                } else {
                    for (StopCandidate c : fromCandidates) {
                        out.println("<option value=\"" + c.stop_id + "\">"
                                + esc(c.stop_name) + "</option>");
                    }
                }
                out.println("</select>");
                out.println("</div>");

  
                // 到着地候補
                out.println("<div class=\"field\">");
                out.println("<label class=\"label\" for=\"to_id\">到着（候補）</label>");
                out.println("<select class=\"select\" id=\"to_id\" name=\"to_id\">");
                if (toId != null) {
                    LatLon fixed = getStopById(conn, toId);
                    if (fixed != null) {
                        out.println("<option value=\"" + fixed.stopid + "\" selected>" + esc(fixed.name) + "</option>");
                    }
                } else {
                    for (StopCandidate c : toCandidates) {
                        out.println("<option value=\"" + c.stop_id + "\">"
                                + esc(c.stop_name) + "</option>");
                    }
                }
                out.println("</select>");
                out.println("</div>");
                

                // 再検索表示
                out.println("<div class=\"actions\">");
                out.println("<input class=\"btn\" type=\"submit\" value=\"この候補で検索\"/>");
                out.println("<span class=\"muted\">※ 候補を選んで再検索します</span>");
                out.println("</div>");

                out.println("</form>");

                // ここでページ終了（下の検索SQLへ行かない）
                out.println("</div></div></body></html>");
                return;
            }
            
            // 出発地/到着地 を確定 -> その検索に入る
            LatLon fromll = getStopById(conn, fromId);
            LatLon toll   = getStopById(conn, toId);
            
            
            // 問い合わせ文
            String sql = ""
                    + "SELECT "
                    + "  r.route_name, "
                    + "  t.trip_name, "
                    + "  t.trip_datetime, "
                    + "  sf.stop_name AS from_stop, "
                    + "  st.stop_name AS to_stop, "
                    + "  sa_from.departure_time AS dep_time, "
                    + "  sa_to.arrival_time AS arr_time, "
                    + "  sa_from.arrival_order AS from_order, "
                    + "  sa_to.arrival_order AS to_order "
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

            // 平日だけ絞る（休日専用便を落とす）
            if ("平日".equals(day)) {
                sql += " AND t.trip_datetime IN ('全日', '平日') ";
            } else {
            	sql += " AND t.trip_datetime IN ('全日', '休日') ";
            }

            // 最短＝到着時刻が一番早い順（同着なら出発が早い方）
            sql += " ORDER BY sa_to.arrival_time ASC, sa_from.departure_time ASC ";
            sql += " LIMIT 3 ";

            // ？に対応する部分の変数を入れる
            ps = conn.prepareStatement(sql);

            int idx = 1;
            ps.setInt(idx++, fromll.stopid);
            ps.setInt(idx++, toll.stopid);
            ps.setString(idx++, baseTime);

            // DB取得を実行
            rs = ps.executeQuery();
            
            
            // ----------
            // 結果を HTML で表示
            out.println("<h3 class=\"result-title\">結果</h3>");
            out.println("<p class=\"muted\">（指定時刻以降に出発する便から、到着が早い順に表示）</p>");

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



            final int WALK_RADIUS_M = 500;
            final int NEAR_LIMIT = 30;

            List<NearbyStop> fromNearby = nearbyStops(conn, fromId, WALK_RADIUS_M, NEAR_LIMIT);

            // ★(重要) もし nearbyStops が自分自身(fromId)を含まない実装なら、ここで追加しておく
            // fromNearby.add(0, new NearbyStop(fromId, 0.0, /*name*/ null));

            List<TransferPath> transfers = new ArrayList<>();

            for (NearbyStop fs : fromNearby) {

                // (A) 出発地(fromId)→ fs.stopId まで歩く時間をまず足す
                int walk0 = (fs.distance <= 1e-9) ? 0 : walkingminutes(fs.distance);
                String base1 = addMinutes(baseTime, walk0);

                // ★(重要) fromId じゃなく fs.stopId を起点にする
                List<OutgoingOption> mids = listTransferCandidates(conn, fs.stopId, base1, day, MID_LIMIT);

                for (OutgoingOption mid : mids) {
                    List<NearbyStop> nearMid = nearbyStops(conn, mid.midStopId, WALK_RADIUS_M, NEAR_LIMIT);

                    for (NearbyStop ns : nearMid) {

                        // (B) mid→ns まで歩く時間（同一停留所なら0分にする）
                        int walk1 = (ns.stopId == mid.midStopId || ns.distance <= 1e-9) ? 0 : walkingminutes(ns.distance);

                        // 2本目に乗れる最短時刻 = mid到着 + 乗換最低時間 + 徒歩時間
                        String base2 = addMinutes(mid.arrTime, TRANSFER_MIN + walk1);

                        // 2本目: ns.stopId -> toId
                        List<DirectPath> leg2list = searchDirect(conn, ns.stopId, toId, base2, day, 1);
                        if (leg2list.isEmpty()) continue;
                        DirectPath leg2 = leg2list.get(0);

                        // 1本目: fs.stopId -> mid.midStopId （★ここも fs.stopId）
                        List<DirectPath> leg1list = searchDirect(conn, fs.stopId, mid.midStopId, base1, day, 1);
                        if (leg1list.isEmpty()) continue;
                        DirectPath leg1 = leg1list.get(0);

                        // 総所要時間は「最初に検索した baseTime から最終到着まで」で計算すると自然
                        int totalMin = (int) java.time.Duration.between(
                            java.time.LocalTime.parse(baseTime),
                            java.time.LocalTime.parse(leg2.arrTime)
                        ).toMinutes();

                        transfers.add(new TransferPath(leg1, leg2, totalMin));

                        break; // このmidは1件見つかったら次
                    }

                    if (transfers.size() >= RESULT_LIMIT) break;
                }

                if (transfers.size() >= RESULT_LIMIT) break;
            }

            for (TransferPath tp : transfers) {
                printTransferRow(out, tp);
            }


            // -----------------------------------------------------------------------------------------------

            // 徒歩関係
            
            if(fromll == null || toll  == null) {
            	out.println("<tr>");
            	out.println("<td>徒歩</td><td>-</td><td>-</td>");
                out.println("<td colspan=\"4\">徒歩計算できません（場所が見つかりません）</td>");
                out.println("</tr>");
            } else {           	
            	double distance = distanceMeters(fromll.lat, fromll.lon, toll.lat, toll.lon);
            	int walkmin = walkingminutes(distance);
                // baseTime は "HH:mm:ss" なのでそれで parse する
                LocalTime dep = LocalTime.parse(baseTime, DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime arr = dep.plusMinutes(walkmin);
                String depstr = dep.format(DateTimeFormatter.ofPattern("HH:mm"));
                String arrstr = arr.format(DateTimeFormatter.ofPattern("HH:mm"));
            
            out.println("<tr>");
            out.println("<td>" + "徒歩" + "</td>");
            out.println("<td>" + "-" + "</td>");
            out.println("<td>" + esc(fromll.name) + "</td>");
            out.println("<td>" + esc(toll.name) + "</td>");
            out.println("<td>" + esc(depstr) + " → " + esc(arrstr)  + "</td>");
            out.println("<td>" + walkmin + "分 (約" + (int)distance + "m)" + "</td>");
            out.println("</tr>");

            }

            
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

    protected void doPost(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    public void destroy() {
    }

    // =================
    // 便利関数
    // =================

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
    
    // 緯度経度を渡すためのクラス
    private static class LatLon {
    	final int stopid;
    	final double lat;
    	final double lon;
    	final String name;
    	
    	LatLon(int stopid, double lat, double lon, String name) {
    		this.stopid = stopid;
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
    		ps.setInt(idx++, stop_id);             // 完全一致
    		
    		try (ResultSet rs = ps.executeQuery()) {
    			if(!rs.next()) return null;
    			
    			int stopid = rs.getInt("stop_id");
    			String name = rs.getString("stop_name");
    			double lat = rs.getDouble("stop_latitude");
    			double lon = rs.getDouble("stop_longitude");
    			return new LatLon (stopid,lat, lon, name);
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
    private int walkingminutes(double r) {
    	double meter_correction = 1.25; // 直線 -> 道のり は 1.25倍
    	double meter_per_minutes = 80;  // 歩く速さは分速80m
    	int minutes = (int) Math.ceil( r * meter_correction / meter_per_minutes);
        return Math.max(1,	 minutes);
    }
    
    // 出発地/目的地 の候補
    private static class StopCandidate {
    	final int stop_id;
    	final String stop_name;
    	final String stop_type;
    	
    	StopCandidate(int stop_id, String stop_name, String stop_type) {
    		this.stop_id = stop_id;
    		this.stop_name = stop_name;
    		this.stop_type = stop_type;
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
    	          String type = rs.getString("stop_type");
    	          list.add(new StopCandidate(stopId, name, type));
    	        }
    	    }
    	}

    return list;
    }

    // 移動一回を返すためのクラス
    private static class DirectPath {
        final int tripId;
        final String routeName;
        final String tripName;
        final int fromStopId;
        final String fromStopName;
        final String depTime; // "HH:MM"
        final int toStopId;
        final String toStopName;
        final String arrTime; // "HH:MM"

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
            + "JOIN route_information r ON r.route_id = rt.route_id "   // ←ここがroute_id問題の場所
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
                        rs.getString("dep_time"),
                        rs.getInt("to_stop_id"),
                        rs.getString("to_stop_name"),
                        rs.getString("arr_time")
                    ));
                }
            }
        }
        return list;
    }
	
    // 乗換一回の前半の移動
    private static class OutgoingOption {
        final int tripId;
        final int midStopId;
        final String midStopName;
        final String arrTime; // mid到着時刻

        OutgoingOption(int tripId, int midStopId, String midStopName, String arrTime) {
            this.tripId = tripId;
            this.midStopId = midStopId;
            this.midStopName = midStopName;
            this.arrTime = arrTime;
        }
    }

    // 出発地からいける停留所を全部調べる
    private List<OutgoingOption> listTransferCandidates(
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

        List<OutgoingOption> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, fromStopId);
            ps.setString(idx++, baseTime);
            ps.setInt(idx++, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new OutgoingOption(
                        rs.getInt("trip_id"),
                        rs.getInt("mid_stop_id"),
                        rs.getString("mid_stop_name"),
                        rs.getString("arr_time")
                    ));
                }
            }
        }
        return list;
    }
    
    // 乗換の前後を保持
    private static class TransferPath {
        final DirectPath leg1;  // from -> mid
        final DirectPath leg2;  // mid  -> to
        final int totalMinutes;

        TransferPath(DirectPath leg1, DirectPath leg2, int totalMinutes) {
            this.leg1 = leg1;
            this.leg2 = leg2;
            this.totalMinutes = totalMinutes;
        }
    }

    // 停留所感のID,距離,時間を持つ
    private static class NearbyStop {
        final int stopId;
        final String name;
        final int distance;

        NearbyStop(int stopId, String name, int distance) {
        	this.stopId = stopId;
            this.distance = distance;
            this.name = name;
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

        // 自分自身(0m)も先頭に入れておくと「徒歩なし」も同じ枠組みで扱える
        // ただしSQL結果に自分が含まれるので、重複するなら除去してもOK
        // ここは簡単に「先頭が自分じゃなければ足す」にしておく
        if (tmp.isEmpty() || tmp.get(0).stopId != centerStopId) {
          tmp.add(0, new NearbyStop(centerStopId, centerstop.name, 1));
        }

        if (tmp.size() > limit) return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
      }
    
    // 結果の表を一列表示する関数
    private void printTransferRow(PrintWriter out, TransferPath tp) {
        String route = esc(tp.leg1.routeName) + " → " + esc(tp.leg2.routeName);
        String trip  = esc(tp.leg1.tripName) + " → " + esc(tp.leg2.tripName);
        String from  = esc(tp.leg1.fromStopName);
        String to    = esc(tp.leg2.toStopName);
        String time  = esc(tp.leg1.depTime) + " → " + esc(tp.leg2.arrTime);
        String dur   = tp.totalMinutes + "分";

        out.println("<tr>");
        out.println("<td>" + route + "</td>");
        out.println("<td>" + trip  + "</td>");
        out.println("<td>" + from  + "</td>");
        out.println("<td>" + to    + "</td>");
        out.println("<td>" + time  + "</td>");
        out.println("<td>" + dur   + "</td>");
        out.println("</tr>");
    }
}
