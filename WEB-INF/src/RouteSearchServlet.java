import static util.HtmlUtils.esc;
import static util.HtmlUtils.option;
import static util.TimeUtils.addMinutes;
import static util.TimeUtils.diffMinutes;
import static util.TimeUtils.now;
import static util.TimeUtils.hhmm;
import static util.GeoUtils.distanceMeters;
import static util.GeoUtils.walkingMinutes;
import static util.GeoUtils.ridingMinutes;

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

    // ------- いじって性能を変えられる定数群 -------

    // 時間関係定数
    private static final int TRANSFER_MIN = 2; // 乗り換えするのに必要な最低時間
    private static final int BIKE_UNLOCK_MIN = 1; // 借りる / 解錠 の最低時間
    private static final int BIKE_LOCK_MIN = 1; // 返す / 施錠 の最低時間

    // 距離関係定数
    private static final int FROM_RADIUS_M = 1000; // 出発地周りの徒歩圏最大
    private static final int TO_RADIUS_M = 1000; // 到着地周りの徒歩圏最大
    private static final int TRANSFER_RADIUS_M = 300; // 乗換の徒歩圏最大
    private static final int BIKE_PORT_RADIUS_M = 400; // 停留所 と ポート間の徒歩圏最大
    private static final int BIKE_MAX_RIDE_M = 6000; // 自転車移動の最大距離（暴走防止）

    // 探索関係定数
    private static final int MID_LIMIT = 10; // 乗り換え地点候補の探索数上限
    private static final int NEAR_LIMIT = 30; // 乗換経路探索数上限
    private static final int PORT_LIMIT = 5; // 近隣ポートの探索数上限
    private static final int RESULT_LIMIT = 5; // 表示する乗換経路の最大

    // 徒歩/自転車速度関係定数
    private static final double METER_CORRECTION = 1.25; // 徒歩距離補正係数 (直線 -> 道のり)
    private static final double METER_PER_MINUTE = 80.0; // 徒歩の速さは 80m/分
    private static final double BIKE_METER_CORRECTION = 1.5; // 自転車距離補正係数 (直線 -> 自転車通行可能な道のり)
    private static final double BIKE_METER_PER_MINUTE = 250.0; // 自転車の速さは 250m/分

    // --------------------------------------------


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
        String originstop    = routeRequest.fromStop;
        String deststop      = routeRequest.toStop;
        String day           = routeRequest.day;
        String timemode      = routeRequest.timeMode;
        String timevalue     = routeRequest.timeValue;
        Integer originstopid = routeRequest.fromId;
        Integer deststopid   = routeRequest.toId;
        String baseTime      = routeRequest.baseTime;

        // HTMLの設定部分
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"ja\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\">");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        out.println("<title>RouteSearch</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + request.getContextPath() + "/static/app.css\"/>"); // CSSはapp.css参照        out.println("</head>");
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


        // カード形式で囲む
        out.println("<div class=\"card\">");

        // フォーム形式
        out.println("<form class=\"form\" action=\"" + request.getContextPath() + "/routesearch\" method=\"GET\">");


        // (1) 出発地 / 目的地 => originstop / deststop
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"from_stop\">出発</label>");
        out.println("<input class=\"input\" id=\"from_stop\" type=\"text\" name=\"from_stop\" placeholder=\"例 : 京都駅\" value=\"" + esc(originstop) + "\"/>");
        out.println("</div>");

        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"to_stop\">到着</label>");
        out.println("<input class=\"input\" id=\"to_stop\" type=\"text\" name=\"to_stop\" placeholder=\"例 : 三条駅\" value=\"" + esc(deststop) + "\"/>");
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

        // (4) 時刻選択 time_val
        out.println("<div class=\"field\">");
        out.println("<label class=\"label\" for=\"time_val\">指定時刻（時刻条件=指定時刻のとき）</label>");
        out.println("<input class=\"input\" id=\"time_val\" type=\"time\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");
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


        // -----------------------------------------------------------------------------------------------

        // データベース接続準備
        Connection conn      = null; // 認証 & 接続用
        PreparedStatement ps = null; // DBに送る文章
        ResultSet rs         = null; // DBの結果を受け取る文章
        HttpSession session  = request.getSession();

        try {
            
            conn = DatabaseConfig.getConnection(); // データベース接続実行

        // -----------------------------------------------------------------------------------------------


            // 地点候補を入れるためのリスト
            List<StopSearchCandidate> originCandidates = new ArrayList<>();
            List<StopSearchCandidate> destCandidates   = new ArrayList<>();

            // 決まっていないなら候補を探索 => 件数に応じて次の探索へ
            if (originstopid == null) originCandidates = searchStopCandidates(conn, originstop, 10);
            if (deststopid == null)   destCandidates   = searchStopCandidates(conn, deststop, 10);

            // 0件なら終了 => 入力しなおしへ
            if ((originstopid == null && originCandidates.isEmpty()) || (deststopid == null && destCandidates.isEmpty())) {
                out.println("<p class=\"alert\">出発 / 到着地点が見つかりませんでした.</p>");
                out.println("</div></div></body></html>"); // card/app/body/html を閉じる
                return; 
            }

            // 1件なら自動確定
            if (originstopid == null && originCandidates.size() == 1) originstopid = originCandidates.get(0).stopId;
            if (deststopid   == null && destCandidates.size()   == 1) deststopid   = destCandidates.get(0).stopId;

            // 複数件 (少なくともどちらかが) なら、候補を選ばせる画面を出す
            if (originstopid == null || deststopid == null) {
                out.println("<div class=\"alert\">候補が複数あります. 以下から選択してください.</div>");
                out.println("<form class=\"form\" action=\"routesearch\" method=\"GET\">");

                // 元の入力値も引き継ぐ（これがないと条件が消える）
                out.println("<input type=\"hidden\" name=\"from_stop\" value=\"" + esc(originstop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"to_stop\" value=\"" + esc(deststop) + "\"/>");
                out.println("<input type=\"hidden\" name=\"day\" value=\"" + esc(day) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_mode\" value=\"" + esc(timemode) + "\"/>");
                out.println("<input type=\"hidden\" name=\"time_val\" value=\"" + esc(timevalue) + "\"/>");

                // 出発地
                if (originstopid != null) { // 決まっていたなら
                    out.println("<input type=\"hidden\" name=\"from_id\" value=\"" + originstopid + "\"/>");
                    Stop fixedoriginStop = getStopByStopId(conn, originstopid);
                    if (fixedoriginStop != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">出発 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedoriginStop.name) + " (" + esc(fixedoriginStop.type) + ")</div>");
                        out.println("</div>");
                    }
                } else { // 決まっていないなら
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"from_id\">出発 (候補)</label>");
                    out.println("<select class=\"select\" id=\"from_id\" name=\"from_id\">");
                    for (StopSearchCandidate c : originCandidates) {
                        out.println("<option value=\"" + c.stopId + "\">" + esc(c.stopName) + " (" + esc(c.stopType) + ")</option>");}
                    out.println("</select>");
                    out.println("</div>");
                }

                // 到着地
                if (deststopid != null) { // 決まっていたなら
                    out.println("<input type=\"hidden\" name=\"to_id\" value=\"" + deststopid + "\"/>");

                    Stop fixedDestStop = getStopByStopId(conn, deststopid);
                    if (fixedDestStop != null) {
                        out.println("<div class=\"field\">");
                        out.println("<label class=\"label\">到着 (確定)</label>");
                        out.println("<div class=\"fixed\">" + esc(fixedDestStop.name) + " (" + esc(fixedDestStop.type) + ")</div>");
                        out.println("</div>");
                    }
                } else { // 決まっていないなら
                    out.println("<div class=\"field\">");
                    out.println("<label class=\"label\" for=\"to_id\">到着 (候補)</label>");
                    out.println("<select class=\"select\" id=\"to_id\" name=\"to_id\">");
                    for (StopSearchCandidate c : destCandidates) {
                        out.println("<option value=\"" + c.stopId + "\">" + esc(c.stopName) + " (" + esc(c.stopType) + ")</option>");}
                    out.println("</select>");
                    out.println("</div>");
                }

                // 再検索表示
                out.println("<div class=\"actions\">");
                out.println("<input class=\"btn\" type=\"submit\" value=\"この候補で検索\"/>");
                out.println("</div>");
                out.println("</form>");
                return;
            }

        // -----------------------------------------------------------------------------------------------

            // 経路探索 (最重要)

            // ---- part 0 (前情報整理) ----

            // 出発地/到着地 を確定 -> その検索に入る
            Stop originStop = getStopByStopId(conn, originstopid);
            Stop destStop = getStopByStopId(conn, deststopid);

            session.setAttribute("lastOriginStopName", originStop != null ? originStop.name : "");
            session.setAttribute("lastOriginStopType", originStop != null ? originStop.type : "");
            session.setAttribute("lastDestStopName", destStop != null ? destStop.name : "");
            session.setAttribute("lastDestStopType", destStop != null ? destStop.type : "");

            // 出発地 / 目的地 の近くの停留所を探索
            List<NearbyStop> stopsNearOrigin = nearbyStopsById(conn, originstopid, FROM_RADIUS_M, NEAR_LIMIT);
            List<NearbyStop> stopsNearDest = nearbyStopsById(conn, deststopid, TO_RADIUS_M, NEAR_LIMIT);

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

                    int rideMin = ridingMinutes(rideDistance, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                    String bikeEndTime = addMinutes(addMinutes(bikeStartTime, rideMin), BIKE_LOCK_MIN);

                    int walkToDestinationDistance = distanceMeters(toPort.lat, toPort.lon, destStop.lat, destStop.lon);
                    int walkToDestinationMin = walkingMinutes(walkToDestinationDistance, METER_CORRECTION,
                            METER_PER_MINUTE);
                    WalkPath walkToDestination = new WalkPath(toPort.portName, destStop.name, walkToDestinationDistance,
                            walkToDestinationMin);
                    String endTime = addMinutes(bikeEndTime, walkToDestinationMin);
                    int totalMin = diffMinutes(baseTime, endTime);

                    // 自転車移動の情報
                        String operatorContact = normalizeContact(fromPort.operatorContact, fromPort.operatorName);
                        BikePath bike = new BikePath(fromPort.operatorId, fromPort.operatorName, operatorContact,
                            fromPort.portId, fromPort.portName, toPort.portId, toPort.portName,
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
                    int totalMin = diffMinutes(originDepartTime, destArrivalTime);

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

                            int totalMin = diffMinutes(originDepartTime, destArrivalTime);

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

                            int rideMin = ridingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
                            String bikeEndTime = addMinutes(bikeStartTime, rideMin + BIKE_LOCK_MIN);

                            int walkToDestDistance = (int) Math
                                    .round(distanceMeters(returnPort.lat, returnPort.lon, destStop.lat, destStop.lon));
                            int walkToDestMin = walkingMinutes(walkToDestDistance, METER_CORRECTION, METER_PER_MINUTE);
                            WalkPath walkToDest = new WalkPath(returnPort.portName, destStop.name, walkToDestDistance,
                                    walkToDestMin);

                            String arrivalTimeToDest = addMinutes(bikeEndTime, walkToDestMin);
                            int totalMin = diffMinutes(originDepartTime, arrivalTimeToDest);

                            String key = "TB:" + leg1.tripId + "|" + startPort.operatorId + ":" + startPort.portId
                                    + "->" + returnPort.portId;
                            if (!seenTBKeys.add(key))
                                continue;

                            String operatorContact = normalizeContact(startPort.operatorContact, startPort.operatorName);
                            BikePath bike = new BikePath(startPort.operatorId, startPort.operatorName,
                                    operatorContact,
                                    startPort.portId, startPort.portName, returnPort.portId, returnPort.portName,
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

                    int rideMin = ridingMinutes(rideDist, BIKE_METER_CORRECTION, BIKE_METER_PER_MINUTE);
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
                            int totalMin = diffMinutes(baseTime, endTime);

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
                            startPort.portId,
                            startPort.portName,
                            returnPort.portId,
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

    private RouteRequest parseRequest(HttpServletRequest request) {
        RouteRequest rr = new RouteRequest();

        rr.fromStop = request.getParameter("from_stop");
        rr.toStop = request.getParameter("to_stop");
        rr.day = request.getParameter("day");
        rr.timeMode = request.getParameter("time_mode");
        rr.timeValue = request.getParameter("time_val");
        String fromIdStr = request.getParameter("from_id");
        String toIdStr = request.getParameter("to_id");

        // NULL => 空文字列 に変換 (エラー対策)
        if (rr.fromStop == null)
            rr.fromStop = "";
        if (rr.toStop == null)
            rr.toStop = "";
        if (rr.day == null || rr.day.isEmpty())
            rr.day = "平日";
        if (rr.timeMode == null)
            rr.timeMode = "now";
        if (rr.timeValue == null)
            rr.timeValue = "";

        // 文字列 -> 数値
        if (fromIdStr != null && !fromIdStr.trim().isEmpty()) {
            try {
                rr.fromId = Integer.valueOf(fromIdStr);
            } catch (NumberFormatException e) {
                rr.fromId = null;
            }
        }
        if (toIdStr != null && !toIdStr.trim().isEmpty()) {
            try {
                rr.toId = Integer.valueOf(toIdStr);
            } catch (NumberFormatException e) {
                rr.toId = null;
            }
        }

        // 入力バリデーション
        if (rr.fromStop.isEmpty() || rr.toStop.isEmpty()) {
            rr.errorMessage = "出発と到着を入力して検索してください";
        } else if ("spec".equals(rr.timeMode) && rr.timeValue.isEmpty()) {
            rr.errorMessage = "指定時刻を入力してください";
        }

        // 時間を basetime として統合
        if ("spec".equals(rr.timeMode) && !rr.timeValue.isEmpty()) {
            rr.baseTime = rr.timeValue;
        } else {
            rr.baseTime = now();
        }

        return rr;
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
        out.println("<link rel=\"stylesheet\" href=\"" + req.getContextPath() + "/static/app.css\"/>");
        out.println("</head><body class='page-route-detail'>");

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
                    + chipInfo("時間 " + diffMinutes(dp.leg.depTime, dp.leg.arrTime) + "分");
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
                    + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
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
                    + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
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
                    + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
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
                    + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
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
            out.println("<button class='btn-action hidden' id='startBtn' onclick='startBikeUsage()'>利用開始</button>");
            out.println("<button class='btn-action hidden' id='returnBtn' onclick='returnBike()'>返却</button>");
            out.println("<span class='cancel-wrap'>");
            out.println("<button class='btn-cancel hidden' id='cancelBtn' onclick='cancelReservation()'>キャンセル</button>");
            out.println("<span id='reserveTimer' class='timer-pill reserve hidden'></span>");
            out.println("<span id='useTimer' class='timer-pill use hidden'></span>");
            out.println("</span>");
            out.println("<div class='reservation-note'>※ 予約は30分以内に利用開始してください（30分を過ぎると無効になります）。</div>");
            out.println("<div class='reservation-status' id='statusMsg'></div>");
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
            out.println("function startReserveCountdown(seconds){ clearReserveCountdown(); var el=document.getElementById('reserveTimer'); reserveExpiryAt = Date.now()+seconds*1000; el.style.display='inline-block'; reserveTimerId = setInterval(function(){ var remain=Math.max(0, Math.floor((reserveExpiryAt-Date.now())/1000)); el.textContent='予約残り '+fmtMMSS(remain); if(remain<=0){ clearReserveCountdown(); reservationState='not_reserved'; currentReservationId=null; updateButtonStates(); document.getElementById('statusMsg').textContent='予約の有効期限が切れました。再度予約してください。'; } }, 1000); } ");
            out.println("function clearReserveCountdown(){ if(reserveTimerId){ clearInterval(reserveTimerId); reserveTimerId=null;} var el=document.getElementById('reserveTimer'); if(el){ el.style.display='none'; el.textContent=''; } } ");
            out.println("function startUseTimer(){ clearUseTimer(); var el=document.getElementById('useTimer'); useStartAt=Date.now(); el.style.display='inline-block'; useTimerId=setInterval(function(){ var sec=Math.floor((Date.now()-useStartAt)/1000); el.textContent='利用時間 '+fmtMMSS(sec); }, 1000);} ");
            out.println("function clearUseTimer(){ if(useTimerId){ clearInterval(useTimerId); useTimerId=null;} var el=document.getElementById('useTimer'); if(el){ el.style.display='none'; el.textContent=''; } } ");
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

    // リクエスト入力を束ねるDTO
    private static class RouteRequest {
        String fromStop;
        String toStop;
        String day;
        String timeMode;
        String timeValue;
        Integer fromId;
        Integer toId;
        String baseTime;
        String errorMessage;

        boolean hasError() {
            return errorMessage != null && !errorMessage.isEmpty();
        }
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
    private Stop getStopByStopId(Connection conn, int stopId) throws SQLException {
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
        Stop centerstop = getStopByStopId(conn, centerStopId);
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
        final int fromPortId;
        final String fromPortName;
        final int toPortId;
        final String toPortName;
        final int distanceM;
        final int rideMinutes;
        final String startTime; // "HH:mm" (解錠後)
        final String endTime; // "HH:mm" (到着)

        BikePath(int operatorId, String operatorName, String operatorContact,
                int fromPortId, String fromPortName,
                int toPortId, String toPortName,
                int distanceM, int rideMinutes,
                String startTime, String endTime) {
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.operatorContact = operatorContact;
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
