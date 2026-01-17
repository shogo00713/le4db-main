import static util.HtmlUtils.esc;
import static util.HtmlUtils.option;
import static util.HtmlUtils.nvl;
import static util.HtmlUtils.toStr;
import static util.HtmlUtils.preferNonEmpty;
import static util.TimeUtils.addMinutes;
import static util.TimeUtils.diffMinutes;
import static util.TimeUtils.now;
import static util.TimeUtils.hhmm;
import static util.GeoUtils.distanceMeters;
import static util.GeoUtils.walkingMinutes;
import static util.GeoUtils.ridingMinutes;
import static util.RouteConstants.*;

import model.*;
import dao.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class RouteSearchServlet extends HttpServlet {


    // データベース接続 & 初期化 (DatabaseConfigが大体やってくれる)
    public void init() throws ServletException {
        String iniFilePath = getServletConfig().getServletContext().getRealPath("WEB-INF/le4db.ini");
        try {
            DatabaseConfig.initialize(iniFilePath);
        } catch (Exception e) {
            throw new ServletException("データベース初期化エラー: " + e.getMessage());
        }
    }


    // メインの関数 (doGet)
    protected void doGet(HttpServletRequest request, HttpServletResponse response)throws ServletException, IOException {

        // 詳細ページに飛ぶ場合
        String view = request.getParameter("view");
        if ("detail".equals(view)) {
            renderDetailPage(request, response);
            return;
        }

        // ルート検索の要求を取ってくる
        RouteRequest routeRequest = parseRequest(request);

        response.setContentType("text/html;charset=UTF-8"); // 返すのはHTML
        PrintWriter out = response.getWriter();

        // フォームでやり取りするパラメータを変数として簡単に扱えるように
        String originstop    = routeRequest.originStop;
        String deststop      = routeRequest.destStop;
        String day           = routeRequest.day;
        String timemode      = routeRequest.timeMode;
        String timevalue     = routeRequest.timeValue;
        Integer originstopid = routeRequest.originStopId;
        Integer deststopid   = routeRequest.destStopId;
        String baseTime      = routeRequest.baseTime;

        // HTMLの設定部分
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"ja\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\">");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        out.println("<title>RouteSearch</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + request.getContextPath() + "/static/app.css\"/>"); // CSSはapp.css参照
        out.println("</head>");

        // HTML本文はじまり
        out.println("<body class=\"page-route-search\">"); //
        out.println("<div class=\"app\">");


        // -------- ヘッダー始まり --------

        out.println("<div class=\"header\">");

        // タイトル
        out.println("<div>");
        out.println("<h2 class=\"title\">マルチモーダル路線検索</h2>");
        out.println("<p class=\"subtitle\">出発地/到着地, 運行日, 時刻条件を指定して検索</p>");
        out.println("</div>");

        // 管理者ボタン
        String ctx = request.getContextPath();
        out.println("<a class=\"adminbtn\" href=\"" + ctx + "/portadmin/\">管理者</a>");
        out.println("</div>");

        // -------- ヘッダー終わり --------


        out.println("<div class=\"card\">");
        out.println("<form class=\"form\" action=\"" + request.getContextPath() + "/routesearch\" method=\"GET\">");

        // (1) 出発地 / 目的地 => originstop / deststop
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"from_stop\">出発</label>");
        out.println("<input class=\"input\" id=\"from_stop\" type=\"text\" name=\"originstop\" placeholder=\"例 : 京都駅\" value=\"" + esc(originstop) + "\"/>");
        out.println("</div>");

        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"to_stop\">到着</label>");
        out.println("<input class=\"input\" id=\"to_stop\" type=\"text\" name=\"deststop\" placeholder=\"例 : 三条駅\" value=\"" + esc(deststop) + "\"/>");
        out.println("</div>");

        // (2) 運行日 => day
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"day\">運行日</label>");
        out.println("<select class=\"select\" id=\"day\" name=\"day\">");
        out.println(option("平日", "平日", day));
        out.println(option("休日", "休日", day));
        out.println("</select>");
        out.println("</div>");

        // (3) 時刻指定選択 => time_mode
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"time_mode\">時刻条件</label>");
        out.println("<select class=\"select\" id=\"time_mode\" name=\"time_mode\">");
        out.println(option("now", "現在時刻", timemode));
        out.println(option("spec", "指定時刻", timemode));
        out.println("</select>");
        out.println("</div>");

        // (4) 時刻選択 time_val（spec のときだけ表示したい）
        boolean showTimeVal = "spec".equals(timemode);
        out.println("<div class=\"field\" id=\"timeValField\"" + (showTimeVal ? "" : " style=\\\"display:none;\\\"") + ">");
        out.println("<label class=\"label\" for=\"time_val\">指定時刻（時刻条件=指定時刻のとき）</label>");
        out.println("<input class=\"input\" id=\"time_val\" type=\"time\" name=\"time_val\" value=\"" + esc(timevalue) + "\"" + (showTimeVal ? "" : " disabled") + "/>");
        out.println("</div>");

        // 検索ボタン
        out.println("<div class=\"actions\">");
        out.println("<input class=\"btn\" type=\"submit\" value=\"検索\"/>");
        out.println("</div>");

        out.println("</form>");
        out.println("<div class=\"hr\"></div>"); // hrは区切り線のこと

        // 入力が揃っていない場合はここで終了
        if (routeRequest.hasError()) {
            out.println("<p>" + esc(routeRequest.errorMessage) + "</p>");
            out.println("</body>");
            out.println("</html>");
            return;
        }


        // ---- JavaScriptで時刻指定欄の表示/非表示を制御 ----
        out.println("<script>");
        out.println("(() => {");
        out.println("  const tm = document.getElementById('time_mode');");
        out.println("  const field = document.getElementById('timeValField');");
        out.println("  const tv = document.getElementById('time_val');");
        out.println("  function sync(){");
        out.println("    const show = (tm.value === 'spec');");
        out.println("    field.style.display = show ? '' : 'none';");
        out.println("    tv.disabled = !show;");
        out.println("    if(!show) tv.value = '';");
        out.println("  }");
        out.println("  tm.addEventListener('change', sync);");
        out.println("  sync();");
        out.println("})();");
        out.println("</script>");
        // -----------------------------------------------


        HttpSession session = request.getSession();

        // --------- 候補選択処理 ---------
        try {
            CandidateSelectionResult candResult;
            candResult = selectCandidates(request, out, originstop, deststop, originstopid, deststopid, day, timemode, timevalue);
            if (candResult.shouldReturn) return;
            originstopid = candResult.originstopid;
            deststopid   = candResult.deststopid;

        } catch (Exception e) {
            out.println("<pre>候補選択エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
            return;
        }
        //--------------------------------

        // -------- メイン検索処理 --------
        try {
            RouteResult routeResult = executeSearch(originstopid, deststopid, day, baseTime, session);
            
            // 検索結果をセッションに保存
            session.setAttribute("lastSearchQuery", request.getQueryString());
            session.setAttribute("lastDisplayedResults", routeResult.displayedResults);
            
            // 結果を描画
            renderSearchResults(out, request, routeResult);
            
        } catch (Exception e) {
            out.println("<pre>検索エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
        }
        //--------------------------------

        // HTML本文おわり
        out.println("</div>"); // card
        out.println("</div>"); // app
        out.println("</body>");
        out.println("</html>");
    }



    // --------------- メインメソッド系 ---------------

    // 乗換リクエスト解析メソッド
    private RouteRequest parseRequest(HttpServletRequest request) {

        RouteRequest rr = new RouteRequest();

        rr.originStop   = nvl(request.getParameter("originstop"));
        rr.destStop     = nvl(request.getParameter("deststop"));
        rr.timeMode     = nvl(request.getParameter("time_mode"));
        rr.timeValue    = nvl(request.getParameter("time_val"));
        rr.day          = nvl(request.getParameter("day"));
        
        String originStopIdStr = nvl(request.getParameter("originstopid"));
        String destStopIdStr   = nvl(request.getParameter("deststopid"));

        // デフォルト値設定
        if (rr.timeMode == null || rr.timeMode.isEmpty()) rr.timeMode = "now";
        if (rr.day == null || rr.day.isEmpty()) rr.day = "平日";

        // String -> Integer 変換
        if (originStopIdStr != null && !originStopIdStr.isEmpty()) {
            try {
                rr.originStopId = Integer.valueOf(originStopIdStr);
            } catch (NumberFormatException e) {
                rr.originStopId = null;
            }
        }
        if (destStopIdStr != null && !destStopIdStr.isEmpty()) {
            try {
                rr.destStopId = Integer.valueOf(destStopIdStr);
            } catch (NumberFormatException e) {
                rr.destStopId = null;
            }
        }

        // 入力エラーケース
        if (rr.originStop == null || rr.originStop.isEmpty() ||
            rr.destStop == null || rr.destStop.isEmpty()) {
            rr.errorMessage = "出発地と目的地を指定してください";
            return rr;
        }
        if ("spec".equals(rr.timeMode) && (rr.timeValue == null || rr.timeValue.isEmpty())) {
            rr.errorMessage = "指定時刻を入力してください";
            return rr;
        }

        // baseTimeに統一
        if ("spec".equals(rr.timeMode) && rr.timeValue != null && !rr.timeValue.isEmpty()) {
            rr.baseTime = rr.timeValue;
        } else {
            rr.baseTime = now();
        }
        return rr;
    }

    // 候補選択処理メソッド
    private CandidateSelectionResult selectCandidates(HttpServletRequest request, PrintWriter out, 
            String originstop, String deststop, Integer originstopid, Integer deststopid, 
            String day, String timemode, String timevalue) throws Exception {
        
        CandidateSelectionResult result = new CandidateSelectionResult();
        result.originstopid             = originstopid;
        result.deststopid               = deststopid;
        result.shouldReturn             = false;
        Connection conn                 = null;
        try {
            conn = DatabaseConfig.getConnection();

            // 地点候補を入れるためのリスト
            List<Stop> originCandidates = new ArrayList<>();
            List<Stop> destCandidates = new ArrayList<>();

            // 決まっていないなら候補を探索
            if (originstopid == null) originCandidates = StopQueries.findByName(conn, originstop, 10);
            if (deststopid == null) destCandidates     = StopQueries.findByName(conn, deststop, 10);

            // 0件なら終了
            if ((originstopid == null && originCandidates.isEmpty()) || (deststopid == null && destCandidates.isEmpty())) {
                out.println("<p class=\"alert\">出発 / 到着地点が見つかりませんでした.</p>");
                out.println("</div></div></body></html>");
                result.shouldReturn = true;
                return result;
            }

            // 1件なら自動確定
            if (originstopid == null && originCandidates.size() == 1) originstopid = originCandidates.get(0).id;
            if (deststopid == null && destCandidates.size() == 1) deststopid = destCandidates.get(0).id;

            // 複数件なら候補選択画面を表示
            if (originstopid == null || deststopid == null) {
                out.println("<div class=\"alert\">候補が複数あります. 以下から選択してください.</div>");
                out.println("<form class=\"form\" action=\"routesearch\" method=\"GET\">");

                out.println("<input type=\"hidden\" name=\"originstop\" value=\"" + esc(originstop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"deststop\" value=\"" + esc(deststop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"day\" value=\"" + esc(day) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_mode\" value=\"" + esc(timemode) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");

                // 出発地選択
                if (originstopid != null) {
                    out.println("<input type=\"hidden\" name=\"originstopid\" value=\"" + originstopid + "\"/>");
                Stop fixedoriginStop = StopQueries.getStopById(conn, originstopid);
                    if (fixedoriginStop != null) {
                        out.println("<div class=\"field\"><label class=\"label\">出発 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedoriginStop.name) + " (" + esc(fixedoriginStop.type) + ")</div></div>");
                    }
                } else {
                    out.println("<div class=\"field\"><label class=\"label\" for=\"originstopid\">出発 (候補)</label>");
                    out.println("<select class=\"select\" id=\"originstopid\" name=\"originstopid\">");
                    for (Stop c : originCandidates) out.println("<option value=\"" + c.id + "\">" + esc(c.name) + " (" + esc(c.type) + ")</option>");
                    out.println("</select></div>");
                }

                // 到着地選択
                if (deststopid != null) {
                    out.println("<input type=\"hidden\" name=\"deststopid\" value=\"" + deststopid + "\"/>");
                    Stop fixedDestStop = StopQueries.getStopById(conn, deststopid);
                    if (fixedDestStop != null) {
                        out.println("<div class=\"field\"><label class=\"label\">到着 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedDestStop.name) + " (" + esc(fixedDestStop.type) + ")</div></div>");
                    }
                } else {
                    out.println("<div class=\"field\"><label class=\"label\" for=\"deststopid\">到着 (候補)</label>");
                    out.println("<select class=\"select\" id=\"deststopid\" name=\"deststopid\">");
                    for (Stop c : destCandidates) out.println("<option value=\"" + c.id + "\">" + esc(c.name) + " (" + esc(c.type) + ")</option>");
                    out.println("</select></div>");
                }

                out.println("<div class=\"actions\"><input class=\"btn\" type=\"submit\" value=\"この候補で検索\"/></div></form>");
                result.shouldReturn = true;
                return result;
            }

            result.originstopid = originstopid;
            result.deststopid   = deststopid;

        } finally {
            if (conn != null) conn.close();
        }

        return result;
    }

    // メイン検索処理メソッド
    private RouteResult executeSearch(Integer originstopid, Integer deststopid, String day, String baseTime, HttpSession session) throws Exception {
        RouteResult result = new RouteResult();
        
        Connection conn      = null;
        PreparedStatement ps = null;
        ResultSet rs         = null;
        
        try {
            conn = DatabaseConfig.getConnection();
            
            // 出発地/到着地 を確定 -> その検索に入る
            result.originStop = StopQueries.getStopById(conn, originstopid);
            result.destStop   = StopQueries.getStopById(conn, deststopid);

            // セッションに最後に使った停留所名を保存
            result.lastOriginStopName = result.originStop != null ? result.originStop.name : "";
            result.lastOriginStopType = result.originStop != null ? result.originStop.type : "";
            result.lastDestStopName   = result.destStop   != null ? result.destStop.name   : "";
            result.lastDestStopType   = result.destStop   != null ? result.destStop.type   : "";

            session.setAttribute("lastOriginStopName", result.lastOriginStopName);
            session.setAttribute("lastOriginStopType", result.lastOriginStopType);
            session.setAttribute("lastDestStopName",   result.lastDestStopName);
            session.setAttribute("lastDestStopType",   result.lastDestStopType);

            // 出発地 / 目的地 の近くの停留所を探索
            result.stopsNearOrigin = getNearByStopsByStopId(conn, originstopid, FROM_RADIUS_M, NEAR_LIMIT);
            result.stopsNearDest   = getNearByStopsByStopId(conn, deststopid, TO_RADIUS_M, NEAR_LIMIT);

            // 出発地 / 目的地 の近くのポートを探索
            result.portsNearOrigin = getNearByPortsByLatLon(conn, result.originStop.lat, result.originStop.lon, FROM_RADIUS_M, PORT_LIMIT, true, false);
            result.portsNearDest   = getNearByPortsByLatLon(conn, result.destStop.lat, result.destStop.lon, TO_RADIUS_M, PORT_LIMIT, false, true);

            // 探索する候補数の上限
            final int TRANSFER_CANDIDATE_LIMIT = RESULT_LIMIT * 30;
            final int DIRECT_CANDIDATE_LIMIT   = RESULT_LIMIT * 30;

            // 結果全体を入れるリスト
            result.results = new ArrayList<>();



            // ---- part 1 (徒歩のみ) ----
            int dist           = distanceMeters(result.originStop.lat, result.originStop.lon, result.destStop.lat, result.destStop.lon);
            int walkMin        = walkingMinutes(dist, METER_CORRECTION, METER_PER_MINUTE);
            String arrivalTime = addMinutes(baseTime, walkMin);
            WalkDirectPlan walk  = new WalkDirectPlan(result.originStop.name, result.destStop.name, dist, walkMin, baseTime, arrivalTime);
            result.results.add(new ResultItem(0, arrivalTime, walkMin, "", walk));



            // ---- part 2 (自転車のみ) ----
            final int BIKE_DIRECT_LIMIT = RESULT_LIMIT * 2;
            List<BikeDirectPlan> bikeDirectCandidates = new ArrayList<>();

            // 出発地近くの乗車ポートの候補に対して
            for (PortCandidate fromPort : result.portsNearOrigin) {

                int walkToStartPortMin      = walkingMinutes(fromPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                int walkToStartPortDistance = distanceMeters(result.originStop.lat, result.originStop.lon, fromPort.lat, fromPort.lon);
                WalkPath walkToStartPort    = new WalkPath(result.originStop.name, fromPort.portName, walkToStartPortDistance, walkToStartPortMin);
                String bikeStartTime        = addMinutes(baseTime, walkToStartPortMin + BIKE_UNLOCK_MIN);

                // 目的地近くの降車ポートの候補に対して
                for (PortCandidate toPort : result.portsNearDest) {
                    int rideDistance = distanceMeters(fromPort.lat, fromPort.lon, toPort.lat, toPort.lon);
                    if (rideDistance > BIKE_MAX_RIDE_M)           continue;
                    if (fromPort.operatorId != toPort.operatorId) continue;
                    if (fromPort.portId == toPort.portId)         continue;
 
                    String operatorContact = normalizeContact(fromPort.operatorContact, fromPort.operatorName);
                    int rideMin            = ridingMinutes(rideDistance, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                    String bikeEndTime     = addMinutes(bikeStartTime, rideMin + BIKE_LOCK_MIN);
                    BikePath bike          = new BikePath(fromPort.operatorId, fromPort.operatorName, operatorContact, fromPort.portId, fromPort.portName, toPort.portId, toPort.portName, rideDistance, rideMin, bikeStartTime, bikeEndTime);

                    int walkToDestinationDistance = distanceMeters(toPort.lat, toPort.lon, result.destStop.lat, result.destStop.lon);
                    int walkToDestinationMin      = walkingMinutes(walkToDestinationDistance, METER_CORRECTION, METER_PER_MINUTE);
                    WalkPath walkToDestination    = new WalkPath(toPort.portName, result.destStop.name, walkToDestinationDistance, walkToDestinationMin);

                    String endTime      = addMinutes(bikeEndTime, walkToDestinationMin);
                    int totalMin        = diffMinutes(baseTime, endTime);
                    BikeDirectPlan plan = new BikeDirectPlan(walkToStartPort, bike, walkToDestination, totalMin, baseTime, endTime);
                    bikeDirectCandidates.add(plan);
                }
            }

            // 早い順にソート
            bikeDirectCandidates.sort(Comparator.comparing((BikeDirectPlan p) -> LocalTime.parse(p.endTime)).thenComparingInt(p -> p.totalMin));

            if(bikeDirectCandidates.size() > BIKE_DIRECT_LIMIT) {
                bikeDirectCandidates = bikeDirectCandidates.subList(0, BIKE_DIRECT_LIMIT);
            }



            // ---- part 3 (公共交通 直通) ----
            java.util.Map<Integer, TransitDirectPlan> bestDirectPlanByTripId = new java.util.HashMap<>();

            // 出発地近くの停留所候補に対して
            for (NearByStops boardStop : result.stopsNearOrigin) {

                int walkToBoardStopMin        = walkingMinutes(boardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop      = new WalkPath(result.originStop.name, boardStop.name, boardStop.distance, walkToBoardStopMin);

                // 目的地近くの停留所候補に対して
                for (NearByStops alightStop : result.stopsNearDest) {

                    List<TransitPath> directPathCandidates = TransitQueries.searchDirectTransit(conn, boardStop.stopId, alightStop.stopId, arrivalTimeToBoardStop, day, 1);
                    if (directPathCandidates.isEmpty()) continue;
                    TransitPath leg = directPathCandidates.get(0);

                    int walkToDestMin       = walkingMinutes(alightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                    String originDepartTime = addMinutes(leg.depTime, -walkToBoardStopMin);
                    String destArrivalTime  = addMinutes(leg.arrTime, walkToDestMin);
                    WalkPath walkToDest     = new WalkPath(alightStop.name, result.destStop.name, alightStop.distance, walkToDestMin);
                    int totalMin            = diffMinutes(originDepartTime, destArrivalTime);

                    TransitDirectPlan directPlan      = new TransitDirectPlan(walkToBoardStop, leg, walkToDest, totalMin, originDepartTime, destArrivalTime);
                    TransitDirectPlan currentBestPlan = bestDirectPlanByTripId.get(leg.tripId);
                    if (currentBestPlan == null || betterDirect(directPlan, currentBestPlan)) bestDirectPlanByTripId.put(leg.tripId, directPlan); // その便を使用する現時点の最良プランと比較
                    if (bestDirectPlanByTripId.size() > DIRECT_CANDIDATE_LIMIT) {
                        java.util.List<TransitDirectPlan> sortPlans = new java.util.ArrayList<>(bestDirectPlanByTripId.values());
                        sortPlans.sort(java.util.Comparator.comparing(p -> LocalTime.parse(p.endTime)));
                        sortPlans.subList(DIRECT_CANDIDATE_LIMIT, sortPlans.size()).clear();
                        bestDirectPlanByTripId.clear();
                        for (TransitDirectPlan p : sortPlans) bestDirectPlanByTripId.put(p.leg.tripId, p);
                    }
                }
            }

            List<TransitDirectPlan> resultDirectPlans = new ArrayList<>(bestDirectPlanByTripId.values());
            resultDirectPlans.sort(Comparator.comparing(p -> LocalTime.parse(p.endTime)));
            for (TransitDirectPlan dp : resultDirectPlans) result.results.add(new ResultItem(1, dp.endTime, dp.totalMin, "", dp));


            // ---- part 4 (公共交通 乗換1回) ----
            java.util.Map<String, TransferPath> bestTransferPathByTransferKey = new java.util.HashMap<>();

            // 出発地近くの停留所候補に対して
            for (NearByStops firstBoardStop : result.stopsNearOrigin) {

                int walkTo1BoardStopMin        = walkingMinutes(firstBoardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeTo1BoardStop = addMinutes(baseTime, walkTo1BoardStopMin);
                WalkPath walkTo1BoardStop      = new WalkPath(result.originStop.name, firstBoardStop.name, firstBoardStop.distance, walkTo1BoardStopMin);

                List<AlightStopCandidate> firstAlightStopCandidates = listTransferCandidates(conn, firstBoardStop.stopId, arrivalTimeTo1BoardStop, day, MID_LIMIT);

                // 乗換降車停留所候補に対して
                for (AlightStopCandidate firstAlightStop : firstAlightStopCandidates) {

                    List<TransitPath> leg1Candidates = TransitQueries.searchDirectTransit(conn, firstBoardStop.stopId, firstAlightStop.stopId, arrivalTimeTo1BoardStop, day, 1);
                    if (leg1Candidates.isEmpty()) continue;
                    TransitPath leg1 = leg1Candidates.get(0);

                    String originDepartTime = addMinutes(leg1.depTime, -walkTo1BoardStopMin);

                    List<NearByStops> stopsNearFirstAlight = getNearByStopsByStopId(conn, firstAlightStop.stopId, TRANSFER_RADIUS_M, NEAR_LIMIT);

                    // 乗換乗車停留所候補に対して
                    for (NearByStops secondBoardStop : stopsNearFirstAlight) {

                        int walkTransferMin                 = walkingMinutes(secondBoardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                        String arrivalTimeToSecondBoardStop = addMinutes(leg1.arrTime, walkTransferMin);
                        if (diffMinutes(leg1.arrTime, arrivalTimeToSecondBoardStop) < TRANSFER_MIN) continue;
                        WalkPath walkTransfer               = new WalkPath(firstAlightStop.stopName, secondBoardStop.name, secondBoardStop.distance, walkTransferMin);

                        // 目的地近くの停留所候補に対して
                        for (NearByStops secondAlightStop : result.stopsNearDest) {

                            List<TransitPath> leg2Candidates = TransitQueries.searchDirectTransit(conn, secondBoardStop.stopId, secondAlightStop.stopId, arrivalTimeToSecondBoardStop, day, 1);
                            if (leg2Candidates.isEmpty()) continue;
                            TransitPath leg2 = leg2Candidates.get(0);

                            int walkToDestMin      = walkingMinutes(secondAlightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                            String destArrivalTime = addMinutes(leg2.arrTime, walkToDestMin);
                            WalkPath walkToDest    = new WalkPath(secondAlightStop.name, result.destStop.name, secondAlightStop.distance, walkToDestMin);
                            int totalMin           = diffMinutes(originDepartTime, destArrivalTime);

                            String key = "TT:" + leg1.tripId + "|" + leg2.tripId + "|" + firstAlightStop.stopId;

                            TransferPath tp = new TransferPath(walkTo1BoardStop, leg1, walkTransfer, leg2, walkToDest, totalMin, originDepartTime, destArrivalTime);
                            TransferPath currentBest = bestTransferPathByTransferKey.get(key);
                            if (currentBest == null || betterTransfer(tp, currentBest)) bestTransferPathByTransferKey.put(key, tp);

                            if (bestTransferPathByTransferKey.size() > TRANSFER_CANDIDATE_LIMIT) {
                                java.util.List<java.util.Map.Entry<String, TransferPath>> sortPlans = new java.util.ArrayList<>(bestTransferPathByTransferKey.entrySet());
                                sortPlans.sort(java.util.Comparator.comparing(e -> java.time.LocalTime.parse(e.getValue().endTime)));
                                for (int i = TRANSFER_CANDIDATE_LIMIT; i < sortPlans.size(); i++) bestTransferPathByTransferKey.remove(sortPlans.get(i).getKey());
                            }
                        }
                    }
                }
            }
            java.util.List<TransferPath> transferPlans = new java.util.ArrayList<>(bestTransferPathByTransferKey.values());
            transferPlans.sort(java.util.Comparator.comparing(p -> java.time.LocalTime.parse(p.endTime)));
            for (TransferPath p : transferPlans) {
                result.results.add(new ResultItem(2, p.endTime, p.totalMin, p.leg1.routeName, p));
            }



            // ---- part 5 (公共交通 -> 自転車) ----
            java.util.Map<String, TransferTransitBike> bestTransferBikePlanByTBKey = new java.util.HashMap<>();
            java.util.Map<Integer, java.util.List<PortCandidate>> toPortsByOp = groupPortsByOperator(result.portsNearDest);
            final int TRANSFER_BIKE_LIMIT = RESULT_LIMIT * 10;

            // 出発地近くの停留所候補に対して
            for (NearByStops boardStop : result.stopsNearOrigin) {

                int walkToBoardStopMin        = walkingMinutes(boardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop      = new WalkPath(result.originStop.name, boardStop.name, boardStop.distance, walkToBoardStopMin);

                List<AlightStopCandidate> firstAlightStopCandidates = listTransferCandidates(conn, boardStop.stopId, arrivalTimeToBoardStop, day, MID_LIMIT);

                // 乗換降車停留所候補に対して
                for (AlightStopCandidate firstAlightStop : firstAlightStopCandidates) {

                    List<TransitPath> leg1Candidates = TransitQueries.searchDirectTransit(conn, boardStop.stopId, firstAlightStop.stopId, arrivalTimeToBoardStop, day, 1);
                    if (leg1Candidates.isEmpty()) continue;
                    TransitPath leg1 = leg1Candidates.get(0);

                    String originDepartTime = addMinutes(leg1.depTime, -walkToBoardStopMin);

                    List<PortCandidate> startPortCandidates = getNearByPortsByLatLon(conn, firstAlightStop.lat, firstAlightStop.lon, BIKE_PORT_RADIUS_M, PORT_LIMIT, true, false);

                    // 乗車ポート候補に対して
                    for (PortCandidate startPort : startPortCandidates) {

                        java.util.List<PortCandidate> returnPortCandidates = toPortsByOp.get(startPort.operatorId);
                        if (returnPortCandidates == null) continue;

                        int walkTransferDistance = distanceMeters(firstAlightStop.lat, firstAlightStop.lon, startPort.lat, startPort.lon);
                        int walkTransferMin      = walkingMinutes(walkTransferDistance, METER_CORRECTION, METER_PER_MINUTE);
                        WalkPath walkTransfer    = new WalkPath(firstAlightStop.stopName, startPort.portName, walkTransferDistance, walkTransferMin);
                        String bikeStartTime     = addMinutes(leg1.arrTime, TRANSFER_MIN + walkTransferMin + BIKE_UNLOCK_MIN);

                        // 降車ポート候補に対して
                        for (PortCandidate returnPort : returnPortCandidates) {

                            if (startPort.portId == returnPort.portId) continue;
                            int rideDist       = distanceMeters(startPort.lat, startPort.lon, returnPort.lat, returnPort.lon);
                            if (rideDist > BIKE_MAX_RIDE_M) continue;
                            int rideMin        = ridingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                            String bikeEndTime = addMinutes(bikeStartTime, rideMin + BIKE_LOCK_MIN);

                            int walkToDestDistance = distanceMeters(returnPort.lat, returnPort.lon, result.destStop.lat, result.destStop.lon);
                            int walkToDestMin      = walkingMinutes(walkToDestDistance, METER_CORRECTION, METER_PER_MINUTE);
                            WalkPath walkToDest    = new WalkPath(returnPort.portName, result.destStop.name, walkToDestDistance, walkToDestMin);

                            String arrivalTimeToDest = addMinutes(bikeEndTime, walkToDestMin);
                            int totalMin             = diffMinutes(originDepartTime, arrivalTimeToDest);

                            String key = "TB:" + leg1.tripId + "|" + startPort.operatorId + ":" + startPort.portId + "->" + returnPort.portId;

                            String operatorContact = normalizeContact(startPort.operatorContact, startPort.operatorName);
                            BikePath bike = new BikePath(startPort.operatorId, startPort.operatorName, operatorContact, startPort.portId, startPort.portName, returnPort.portId, returnPort.portName, rideDist, rideMin, bikeStartTime, bikeEndTime);
                            TransferTransitBike plan = new TransferTransitBike(walkToBoardStop, leg1, walkTransfer, bike, walkToDest, totalMin, originDepartTime, arrivalTimeToDest);

                            TransferTransitBike currentBest = bestTransferBikePlanByTBKey.get(key);
                            if (currentBest == null || betterTransferBike(plan, currentBest)) bestTransferBikePlanByTBKey.put(key, plan);

                            if (bestTransferBikePlanByTBKey.size() > TRANSFER_BIKE_LIMIT) {
                                java.util.List<java.util.Map.Entry<String, TransferTransitBike>> sortPlans = new java.util.ArrayList<>(bestTransferBikePlanByTBKey.entrySet());
                                sortPlans.sort(java.util.Comparator.comparing(e -> java.time.LocalTime.parse(e.getValue().endTime)));
                                for (int i = TRANSFER_BIKE_LIMIT; i < sortPlans.size(); i++)
                                    bestTransferBikePlanByTBKey.remove(sortPlans.get(i).getKey());
                            }
                        }
                    }
                }
            }

            for (TransferTransitBike plan : bestTransferBikePlanByTBKey.values())
                result.results.add(new ResultItem(2, plan.endTime, plan.totalMin, plan.leg1.routeName, plan));



            // ---- part 6 (自転車 -> 公共交通) ----
            java.util.Map<String, TransferBikeTransit> bestBikeTransitPlanByBTKey = new java.util.HashMap<>();
            List<PortCandidate> usePortsCandidates;
            {
                List<Stop> boardStopCandidates = StopQueries.findNearbyStops(conn, result.originStop.lat, result.originStop.lon, BIKE_MAX_RIDE_M);
                List<NearByStops> nearDestTop = result.stopsNearDest.subList(0, Math.min(8, result.stopsNearDest.size()));
                List<Stop> goodBoards = new ArrayList<>();
                for (Stop boardStop : boardStopCandidates) {
                    boolean ok = false;
                    for (NearByStops nsto : nearDestTop) {
                        if (!TransitQueries.searchDirectTransit(conn, boardStop.id, nsto.stopId, baseTime, day, 1).isEmpty()) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) goodBoards.add(boardStop);
                }
                usePortsCandidates = collectNearbyPortsFromStops(conn, goodBoards, BIKE_PORT_RADIUS_M, PORT_LIMIT, false, true);
                if (usePortsCandidates.isEmpty()) {
                    usePortsCandidates = getNearByPortsByLatLon(conn, result.originStop.lat, result.originStop.lon, BIKE_MAX_RIDE_M, 30, false, true);
                }
            }

            final int BIKE_TRANSIT_LIMIT = RESULT_LIMIT * 10;
            List<NearByStops> destStopsForBT = result.stopsNearDest.subList(0, Math.min(25, result.stopsNearDest.size()));

            // 出発地近くのポート候補に対して
            for (PortCandidate startPort : result.portsNearOrigin) {

                if (bestBikeTransitPlanByBTKey.size() >= BIKE_TRANSIT_LIMIT) break;

                int walkToStartPortMin   = walkingMinutes(startPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                WalkPath walkToStartPort = new WalkPath(result.originStop.name, startPort.portName, startPort.distance, walkToStartPortMin);
                String bikeStart         = addMinutes(baseTime, walkToStartPortMin + BIKE_UNLOCK_MIN);

                // 目的地近くのポート候補に対して
                for (PortCandidate returnPort : usePortsCandidates) {

                    if (bestBikeTransitPlanByBTKey.size() >= BIKE_TRANSIT_LIMIT) break;

                    int rideDist = distanceMeters(startPort.lat, startPort.lon, returnPort.lat, returnPort.lon);
                    if (rideDist > BIKE_MAX_RIDE_M) continue;
                    if (startPort.operatorId != returnPort.operatorId) continue;
                    if (startPort.portId == returnPort.portId) continue;

                    int rideMin        = ridingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                    String bikeEndTime = addMinutes(bikeStart, rideMin + BIKE_LOCK_MIN);

                    List<Stop> boardStops = StopQueries.findNearbyStops(conn, returnPort.lat, returnPort.lon, TRANSFER_RADIUS_M);

                    // 乗り換え候補の停留所に対して
                    for (Stop boardstop : boardStops) {

                        if (bestBikeTransitPlanByBTKey.size() >= BIKE_TRANSIT_LIMIT) break;

                        int walkTransferDistance = distanceMeters(returnPort.lat, returnPort.lon, boardstop.lat, boardstop.lon);
                        int walkTransferMin = walkingMinutes(walkTransferDistance, METER_CORRECTION, METER_PER_MINUTE);
                        WalkPath walkTransfer = new WalkPath(returnPort.portName, boardstop.name, walkTransferDistance, walkTransferMin);
                        String transitDepartTime = addMinutes(bikeEndTime, TRANSFER_MIN + walkTransferMin);

                        TransitPath bestLeg2 = null;
                        WalkPath bestWalk2 = null;
                        String bestEnd = null;
                        int bestTotal = Integer.MAX_VALUE;

                        // 目的地近くの停留所候補に対して
                        for (NearByStops alightStop : destStopsForBT) {
                            List<TransitPath> leg2Candidates = TransitQueries.searchDirectTransit(conn, boardstop.id, alightStop.stopId, transitDepartTime, day, 1);
                            if (leg2Candidates.isEmpty()) continue;
                            TransitPath leg2 = leg2Candidates.get(0);

                            int walkToDestMin   = walkingMinutes(alightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                            WalkPath walkToDest = new WalkPath(alightStop.name, result.destStop.name, alightStop.distance, walkToDestMin);
  
                            String endTime      = addMinutes(leg2.arrTime, walkToDestMin);
                            int totalMin        = diffMinutes(baseTime, endTime);

                            // 後半の公共交通の到着時刻が早いものを優先
                            if (bestEnd == null || LocalTime.parse(endTime).isBefore(LocalTime.parse(bestEnd))) {
                                bestEnd = endTime;
                                bestTotal = totalMin;
                                bestLeg2 = leg2;
                                bestWalk2 = walkToDest;
                            }
                        }

                        if (bestLeg2 == null) continue;

                        String bikeEndAdj   = addMinutes(bestLeg2.depTime, -(TRANSFER_MIN + walkTransferMin));
                        String bikeStartAdj = addMinutes(bikeEndAdj, -(rideMin + BIKE_LOCK_MIN));

                        String key = "BT:" + startPort.operatorId + ":" + startPort.portId + "->" + returnPort.portId
                                   + "|" + bestLeg2.tripId + ":" + bestLeg2.fromStopId + ":" + bestLeg2.toStopId;

                        String operatorContact = normalizeContact(startPort.operatorContact, startPort.operatorName);

                        // ★ここだけ bikeStart/bikeEndTime を差し替え
                        BikePath bike = new BikePath(
                                startPort.operatorId, startPort.operatorName, operatorContact,
                                startPort.portId, startPort.portName,
                                returnPort.portId, returnPort.portName,
                                rideDist, rideMin,
                                bikeStartAdj, bikeEndAdj
                        );

                        TransferBikeTransit plan = new TransferBikeTransit(walkToStartPort, bike, walkTransfer, bestLeg2, bestWalk2, bestTotal, baseTime, bestEnd);

                        TransferBikeTransit currentBest = bestBikeTransitPlanByBTKey.get(key);
                        if (currentBest == null || betterBikeTransit(plan, currentBest)) bestBikeTransitPlanByBTKey.put(key, plan);
                    }
                }
            }

            for (TransferBikeTransit plan : bestBikeTransitPlanByBTKey.values())
                result.results.add(new ResultItem(2, plan.endTime, plan.totalMin, plan.leg2.routeName, plan));

 

            // ---- part final (結果全体のソート) ----
            result.results.sort(Comparator.comparing((ResultItem r) -> r.end).thenComparingInt(r -> r.totalMinutes).thenComparingInt(r -> r.kind));

            // 表示対象を選定
            result.displayedResults = new ArrayList<>();
            java.util.Set<String> usedFirstRoute = new java.util.HashSet<>();
            int shown = 0;

            for (ResultItem resultItem : result.results) {
                if (shown >= RESULT_LIMIT) break;

                if (resultItem.kind == 2) {
                    if (resultItem.firstRoute != null && !resultItem.firstRoute.isEmpty()) {
                        if (!usedFirstRoute.add(resultItem.firstRoute)) continue;
                    }
                }
                result.displayedResults.add(resultItem);
                shown++;
            }

            return result;

        } finally {
            try {
                if (rs != null) rs.close();
            } catch (SQLException e) {}
            try {
                if (ps != null) ps.close();
            } catch (SQLException e) {}
            try {
                if (conn != null) conn.close();
            } catch (SQLException e) {}
        }
    }

    // 検索結果を描画メソッド
    private void renderSearchResults(PrintWriter out, HttpServletRequest request, RouteResult routeResult) {
        out.println("<h3 class=\"result-title\">経路 : " + esc(routeResult.originStop.name) + 
                    "<span class=\"arrow\">→</span>" + esc(routeResult.destStop.name) + "</h3>");
        out.println("<p class=\"muted\">（指定時刻以降に出発する便から, 到着が早い順に表示）</p>");

        out.println("<div class=\"table-wrap\"><table>");
        out.println("<tr><th>経路</th><th>時刻</th><th>所要時間</th><th>詳細</th></tr>");

        int shown = 0;
        for (ResultItem resultItem : routeResult.displayedResults) {
            if (shown >= RESULT_LIMIT) break;

            int rid = shown;
            String detailUrl = request.getContextPath() + "/routesearch?view=detail&rid=" + rid;

            if (resultItem.payload instanceof WalkDirectPlan) {
                printWalkDirectRow(out, (WalkDirectPlan) resultItem.payload, detailUrl);
            } else if (resultItem.payload instanceof TransitDirectPlan) {
                printDirectRow(out, (TransitDirectPlan) resultItem.payload, detailUrl);
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

        out.println("</table></div><br/>");
    }

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

        // --- HTML ---
        out.println("<!DOCTYPE html><html lang='ja'><head>");
        out.println("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>Route Detail</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + req.getContextPath() + "/static/app.css\"/>");
        out.println("</head><body class='page-route-detail'>");

        out.println("<div class='detail-card'>");
        out.println("<a href='" + esc(backUrl) + "' class='back-btn'>← 戻る</a>");
        out.println("<div class='detail-header'>ルート詳細</div>");

        String payloadOrigin = "";
        String payloadDest = "";
        if (item.payload instanceof WalkDirectPlan) {
            WalkDirectPlan wp = (WalkDirectPlan) item.payload;
            payloadOrigin = wp.fromName;
            payloadDest = wp.toName;
        } else if (item.payload instanceof TransitDirectPlan) {
            TransitDirectPlan dp = (TransitDirectPlan) item.payload;
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

        // 徒歩のみ
        if (item.payload instanceof WalkDirectPlan) {
            WalkDirectPlan wp = (WalkDirectPlan) item.payload;
            String main = esc(wp.fromName) + arrow + esc(wp.toName) + " (" + hhmm(wp.startTime) + "→" + hhmm(wp.endTime) + ")";
            String meta = chipInfo("距離 約" + wp.distanceM + "m") + chipInfo("時間 " + wp.totalMin + "分");
            printStep(out, "徒歩", main, meta);
        } 
        // 直通
        else if (item.payload instanceof TransitDirectPlan) {
            TransitDirectPlan dp = (TransitDirectPlan) item.payload;
            printWalk(dp.walk0, out, arrow);
            String mainRide = esc(dp.leg.fromStopName) + " " + hhmm(dp.leg.depTime) + arrow + esc(dp.leg.toStopName) + " " + hhmm(dp.leg.arrTime);
            String metaRide = chipLine(dp.leg.routeName, dp.leg.routeColor) + chipTrip(dp.leg.tripName) + chipInfo("時間 " + diffMinutes(dp.leg.depTime, dp.leg.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);
            printWalk(dp.walk2, out, arrow);
        }
        // 乗換
        else if (item.payload instanceof TransferPath) {
            TransferPath tp = (TransferPath) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow + esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor) + chipTrip(tp.leg1.tripName) + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);
            if (!isZeroWalk(tp.walk1)) {
                String mainWalk1 = esc(tp.walk1.fromName) + arrow + esc(tp.walk1.toName);
                String metaWalk1 = chipInfo("距離 約" + tp.walk1.dist + "m") + chipInfo("時間 " + tp.walk1.min + "分");
                printStep(out, "徒歩", mainWalk1, metaWalk1);
            } else {
                printStep(out, "乗換", "同一地点で乗換", "");
            }
            String mainRide2 = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide2 = chipLine(tp.leg2.routeName, tp.leg2.routeColor) + chipTrip(tp.leg2.tripName) + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide2, metaRide2);
            printWalk(tp.walk2, out, arrow);
        }
        // 自転車のみ
        else if (item.payload instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) item.payload;
            printWalk(bp.walk0, out, arrow);
            String mainBike = esc(bp.bike.fromPortName) + " " + hhmm(bp.bike.startTime) + arrow + esc(bp.bike.toPortName) + " " + hhmm(bp.bike.endTime);
            String metaBike = chipInfo("距離 約" + bp.bike.distanceM + "m") + chipInfo("時間 " + bp.bike.rideMinutes + "分") + chipInfo("事業者 " + bp.bike.operatorName) + chipContact(bp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(bp.walk2, out, arrow);
        }
        // 公共交通 -> 自転車
        else if (item.payload instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow+ esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor) + chipTrip(tp.leg1.tripName) + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);
            printWalk(tp.walk1, out, arrow);
            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m") + chipInfo("時間 " + tp.bike.rideMinutes + "分") + chipInfo("事業者 " + tp.bike.operatorName) + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(tp.walk2, out, arrow);
        } 
        // 自転車 -> 公共交通
        else if (item.payload instanceof TransferBikeTransit) {
            TransferBikeTransit tp = (TransferBikeTransit) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m") + chipInfo("時間 " + tp.bike.rideMinutes + "分") + chipInfo("事業者 " + tp.bike.operatorName) + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(tp.walk1, out, arrow);
            String mainRide = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide = chipLine(tp.leg2.routeName, tp.leg2.routeColor) + chipTrip(tp.leg2.tripName) + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);
            printWalk(tp.walk2, out, arrow);
        }

        out.println("</div>");

        // ---- シェアサイクル予約セクション ----
        boolean hasBikeSegment = (item.payload instanceof BikeDirectPlan) ||
                                 (item.payload instanceof TransferTransitBike) ||
                                 (item.payload instanceof TransferBikeTransit);
        // 変数を統一させる
        if (hasBikeSegment) {
            String bikeOperatorName = "";
            String bikeOperatorContact = "";
            int startPortId = -1;
            int endPortId = -1;
            int bikeOperatorId = 0;

            if (item.payload instanceof BikeDirectPlan) {
                BikeDirectPlan bp = (BikeDirectPlan) item.payload;
                bikeOperatorName = bp.bike.operatorName;
                bikeOperatorContact = bp.bike.operatorContact;
                bikeOperatorId = bp.bike.operatorId;
                startPortId = bp.bike.fromPortId;
                endPortId = bp.bike.toPortId;
            } else if (item.payload instanceof TransferTransitBike) {
                TransferTransitBike tp = (TransferTransitBike) item.payload;
                bikeOperatorName = tp.bike.operatorName;
                bikeOperatorContact = tp.bike.operatorContact;
                bikeOperatorId = tp.bike.operatorId;
                startPortId = tp.bike.fromPortId;
                endPortId = tp.bike.toPortId;
            } else if (item.payload instanceof TransferBikeTransit) {
                TransferBikeTransit tp = (TransferBikeTransit) item.payload;
                bikeOperatorName = tp.bike.operatorName;
                bikeOperatorContact = tp.bike.operatorContact;
                bikeOperatorId = tp.bike.operatorId;
                startPortId = tp.bike.fromPortId;
                endPortId = tp.bike.toPortId;
            }

            out.println("<div class='reservation-section'>");
            out.println("<div class='reservation-title'>🚲 シェアサイクルを予約</div>");
            out.println("<div class='contact-card'>");
            out.println("<p class='contact-name'>" + esc(bikeOperatorName) + "</p>");
            out.println("<p class='contact-contact'>" + esc(bikeOperatorContact) + "</p>");
            out.println("</div>");
            out.println("<div class='reservation-actions'>");
            out.println("<button class='btn-reserve' id='reserveBtn' onclick='reserveBike(" + bikeOperatorId + ")'>予約する</button>");
            out.println("<button class='btn-action' id='startBtn' style='display:none;' onclick='startBikeUsage()'>利用開始</button>");
            out.println("<button class='btn-action' id='returnBtn' style='display:none;' onclick='returnBikeUsage()'>返却</button>");
            out.println("<div class='cancel-area'>");
            out.println("<button class='btn-cancel' id='cancelBtn' style='display:none;' onclick='cancelBikeReservation()'>キャンセル</button>");
            out.println("<span id='timerPill' class='timer-pill' style='display:none;'></span>");
            out.println("</div>");
            out.println("<div id=\"statusMsg\" class=\"reservation-status is-success\">");
            out.println("<span class=\"status-icon\">✓</span>");
            out.println("<span class=\"status-text\">自転車を返却しました。ご利用ありがとうございました。</span>");
            out.println("</div>");
            out.println("</div>");

            out.println("<script>");
            out.println("var reservationState = 'not_reserved';");
            out.println("var currentReservationId = null;");
            out.println("var startPortId = " + (startPortId > 0 ? startPortId : "-1") + ";");
            out.println("var endPortId = " + (endPortId > 0 ? endPortId : "-1") + ";");
            out.println("var reserveTimerId = null;");
            out.println("var reserveExpiryAt = null;");
            out.println("var useTimerId = null;");
            out.println("var useStartAt = null;");
            out.println("");
            out.println("function fmtMMSS(total){var m=Math.floor(total/60),s=total%60;return (m<10?'0'+m:m)+':'+(s<10?'0'+s:s);} ");
            out.println("function startReserveCountdown(seconds){ clearReserveCountdown(); var el=document.getElementById('timerPill'); el.className = 'timer-pill reserve';reserveExpiryAt = Date.now()+seconds*1000; el.style.display='inline-block'; reserveTimerId = setInterval(function(){ var remain=Math.max(0, Math.floor((reserveExpiryAt-Date.now())/1000)); el.textContent='予約残り '+fmtMMSS(remain); if(remain<=0){ clearReserveCountdown(); reservationState='not_reserved'; currentReservationId=null; updateButtonStates(); document.getElementById('statusMsg').textContent='予約の有効期限が切れました。再度予約してください。'; } }, 1000); } ");
            out.println("function clearReserveCountdown(){ if(reserveTimerId){ clearInterval(reserveTimerId); reserveTimerId=null;} var el=document.getElementById('timerPill'); if(el){ el.style.display='none'; el.textContent='timer-pill'; } } ");
            out.println("function startUseTimer(){ clearUseTimer(); var el=document.getElementById('timerPill');el.className = 'timer-pill use';useStartAt=Date.now(); el.style.display='inline-block'; useTimerId=setInterval(function(){ var sec=Math.floor((Date.now()-useStartAt)/1000); el.textContent='利用時間 '+fmtMMSS(sec); }, 1000);} ");
            out.println("function clearUseTimer(){ if(useTimerId){ clearInterval(useTimerId); useTimerId=null;} var el=document.getElementById('timerPill'); if(el){ el.style.display='none'; el.textContent='timer-pill'; } } ");
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
            out.println("        startReserveCountdown(30*60);");
            out.println("        document.getElementById('statusMsg').textContent = '✓ 予約しました。30分以内に利用を開始してください。';");
            out.println("      } else {");
            out.println("        alert('予約に失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  var payload = {action: 'reserve', operator_id: operatorId};");
            out.println("  if (startPortId && startPortId > 0) payload.start_port_id = startPortId;");
            out.println("  xhr.send(JSON.stringify(payload));");
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
            out.println("        clearReserveCountdown();");
            out.println("        updateButtonStates();");
            out.println("        startUseTimer();");
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
            out.println("        clearUseTimer();");
            out.println("        document.getElementById('statusMsg').textContent = '✓ 自転車を返却しました。ご利用ありがとうございました。';");
            out.println("      } else {");
            out.println("        alert('返却に失敗しました: ' + response.error);");
            out.println("      }");
            out.println("    }");
            out.println("  };");
            out.println("  var payload = {action: 'return', reservation_id: currentReservationId};");
            out.println("  if (endPortId && endPortId > 0) payload.return_port_id = endPortId;");
            out.println("  xhr.send(JSON.stringify(payload));");
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
            out.println("        clearReserveCountdown();");
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
            out.println("function returnBikeUsage() { returnBike(); }");
            out.println("function cancelBikeReservation() { cancelReservation(); }");
            out.println("</script>");
        }

        out.println("</div>");
        out.println("</body></html>");
    }

    // --------------------- Stop / Port 系 --------------------

    // stopId 近くの停留所を列挙
    private List<NearByStops> getNearByStopsByStopId(Connection conn, int centerStopId, int radiusM, int limit) throws SQLException {
        Stop centerstop = StopQueries.getStopById(conn, centerStopId);
        if (centerstop == null)
            return new ArrayList<>();

        // 半径radiusMを緯度経度の範囲に雑に変換（高速化）
        double dLat = radiusM / 111000.0;
        double dLon = radiusM / (111000.0 * Math.cos(Math.toRadians(centerstop.lat)));

        String sql = "SELECT stop_id, stop_name, stop_latitude, stop_longitude "
                + "FROM stop_information "
                + "WHERE stop_latitude BETWEEN ? AND ? "
                + "  AND stop_longitude BETWEEN ? AND ?";

        List<NearByStops> tmp = new ArrayList<>();

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
                        tmp.add(new NearByStops(sid, name, meters, lat, lon));
                    }
                }
            }
        }

        // 近い順
        tmp.sort((a, b) -> Integer.compare(a.distance, b.distance));

        if (tmp.isEmpty() || tmp.get(0).stopId != centerStopId) {
            tmp.add(0, new NearByStops(centerStopId, centerstop.name, 0, centerstop.lat, centerstop.lon));
        }

        if (tmp.size() > limit)
            return new ArrayList<>(tmp.subList(0, limit));
        return tmp;
    }

    // ある緯度経度から近くのポートを列挙
    private List<PortCandidate> getNearByPortsByLatLon(Connection conn, double centerLat, double centerLon,
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

    // --------------------- 経路検索系 --------------------



    // --------------------- その他系 --------------------

    // より良い自転車か調べる
    private boolean betterBikeTransit(TransferBikeTransit a, TransferBikeTransit b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        return a.totalMin < b.totalMin;
    }
    
    // より良い直通か調べる
    private boolean betterDirect(TransitDirectPlan a, TransitDirectPlan b) {
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
        return a.totalMin < b.totalMin;
    }

    // より良い乗換か調べる
    private boolean betterTransfer(TransferPath a, TransferPath b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        if (a.totalMin != b.totalMin) return a.totalMin < b.totalMin;

        java.time.LocalTime as = java.time.LocalTime.parse(a.startTime);
        java.time.LocalTime bs = java.time.LocalTime.parse(b.startTime);
        return as.compareTo(bs) > 0; // 同着同時間なら待ちが少ない(出発が遅い)方
    }

    // より良い乗換＋自転車か調べる
    private boolean betterTransferBike(TransferTransitBike a, TransferTransitBike b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        if (a.totalMin != b.totalMin) return a.totalMin < b.totalMin;

        java.time.LocalTime as = java.time.LocalTime.parse(a.startTime);
        java.time.LocalTime bs = java.time.LocalTime.parse(b.startTime);
        return as.compareTo(bs) > 0;
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
            Connection conn, List<Stop> stops, int radiusM, int portLimit, boolean needBikes, boolean needDocks)
            throws SQLException {
        java.util.Map<Integer, PortCandidate> portById = new java.util.HashMap<>();
        for (Stop stop : stops) {
            List<PortCandidate> ports = getNearByPortsByLatLon(conn, stop.lat, stop.lon, radiusM, portLimit, needBikes, needDocks);
            for (PortCandidate p : ports) {
                portById.putIfAbsent(p.portId, p);
            }
        }
        return new ArrayList<>(portById.values());
    }

    // 乗り換えが同一地点かどうかを判断する関数
    private boolean isZeroWalk(WalkPath w) {
        if (w == null)
            return true;
        boolean same = (w.fromName != null && w.toName != null && w.fromName.equals(w.toName));
        return same && w.min == 0 && w.dist == 0;
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
    private String pathHtml(WalkDirectPlan wp) {
        return "<div class=\"path\">" + tagWalk(wp.totalMin + "分") + "</div>";
    }

    private String pathHtml(TransitDirectPlan dp) {
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
    private void printWalkDirectRow(PrintWriter out, WalkDirectPlan wp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(wp) + "</td>");
        out.println("<td>" + esc(hhmm(wp.startTime)) + " → " + esc(hhmm(wp.endTime)) + "</td>");
        out.println("<td>" + wp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 自転車のみ の結果を表示
    private void printBikeDirectRow(PrintWriter out, BikeDirectPlan bp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(bp) + "</td>");
        out.println("<td>" + esc(hhmm(bp.startTime)) + " → " + esc(hhmm(bp.endTime)) + "</td>");
        out.println("<td>" + bp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }

    // 直通 の結果を表示
    private void printDirectRow(PrintWriter out, TransitDirectPlan dp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(dp) + "</td>");
        out.println("<td>" + esc(hhmm(dp.startTime)) + " → " + esc(hhmm(dp.endTime)) + "</td>");
        out.println("<td>" + dp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }

    // 乗換あり の結果表示
    private void printTransferRow(PrintWriter out, TransferPath tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 公共交通 -> 自転車 の結果を表示
    private void printTransitBikeRow(PrintWriter out, TransferTransitBike tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }

    // 自転車 -> 公共交通 の結果を表示
    private void printBikeTransitRow(PrintWriter out, TransferBikeTransit tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }

    private void printWalk (WalkPath w, PrintWriter out, String arrow) {
        if(!isZeroWalk(w)) {
            String main = esc(w.fromName) + arrow + esc(w.toName);
            String meta = chipInfo("距離 約" + w.dist + "m") + chipInfo("時間 " + w.min + "分");
            printStep(out, "徒歩", main, meta);
        }
    }
}
