import static util.HtmlUtils.esc;
import static util.HtmlUtils.nvl;
import static util.HtmlUtils.preferNonEmpty;
import static util.HtmlUtils.toStr;
import static util.HtmlUtils.normalizeContact;
import static util.TimeUtils.addMinutes;
import static util.TimeUtils.diffMinutes;
import static util.TimeUtils.hhmm;
import static util.TimeUtils.now;
import static util.GeoUtils.distanceMeters;
import static util.GeoUtils.walkingMinutes;
import static util.GeoUtils.ridingMinutes;
import static util.RouteConstants.*;
import static util.RouteSearchUtils.*;

import model.*;
import view.HtmlLayout;
import view.RouteDetailView;
import view.RouteSearchView;
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


    // doGet
    protected void doGet(HttpServletRequest request, HttpServletResponse response)throws ServletException, IOException {

        // 詳細ページに飛ぶ場合
        String view = request.getParameter("view");
        if ("detail".equals(view)) {
            RouteDetail m = buildDetailModel(request);
            RouteDetailView.renderDetailPage(request, response, m);
            return;
        }


        // ルート検索の要求を取ってくる
        RouteRequest rr = parseRequest(request);

        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        // セッションは候補選択の再表示にも使うため早めに取得
        HttpSession session = request.getSession();

        CandidateSelectionResult persistedCandidateSelection =
            (CandidateSelectionResult) session.getAttribute("lastCandidateSelection");
        String persistedCandidateKey = (String) session.getAttribute("lastCandidateSelectionKey");
        String currentCandidateKey = rr.originStop + "->" + rr.destStop;

        // フォームでやり取りするパラメータを変数として簡単に扱えるように
        String originstop    = rr.originStop;
        String deststop      = rr.destStop;
        String dayType       = rr.dayType;
        String timeType      = rr.timeType;
        String time          = rr.time;
        Integer originstopid = rr.originStopId;
        Integer deststopid   = rr.destStopId;
        String baseTime      = rr.baseTime;
        String alertMsg = rr.hasError() ? rr.errorMessage : null;

        // ==========================================================================

        // -------- head --------
        HtmlLayout.renderHead(out, request, "RouteSearch", "page-route-search");

        // ==========================================================================

        // -------- header --------
        RouteSearchView.renderHeader(out, request, "マルチモーダル路線検索", "page-route-search");

        // -------- リクエスト入力 --------
        RouteSearchView.renderSearchForm(out, request, originstop, deststop, dayType, timeType, time, alertMsg);

        // 入力が揃っていない場合はここで終了
        if (rr.hasError()) {
        HtmlLayout.renderFoot(out);
        return;
        }

        // --------- 停留所候補選択 ---------
        try {
            CandidateSelectionResult candResult = selectCandidates(originstop, deststop, originstopid, deststopid);

            if (candResult.status == CandidateSelectionResult.Status.NOT_FOUND) {
                session.removeAttribute("lastCandidateSelection");
                session.removeAttribute("lastCandidateSelectionKey");
                out.println("<p class=\"alert\">" + esc(candResult.message) + "</p>");
                HtmlLayout.renderFoot(out);
                return;
            }
            if (candResult.status == CandidateSelectionResult.Status.NEED_CHOICE) {
                session.setAttribute("lastCandidateSelection", candResult);
                session.setAttribute("lastCandidateSelectionKey", currentCandidateKey);
                RouteSearchView.renderCandidateSelectionForm(out, request, rr, candResult);
                HtmlLayout.renderFoot(out);
                return;
            }
            // 候補選択が完了したので古い候補一覧は破棄
            if (persistedCandidateSelection != null && !currentCandidateKey.equals(persistedCandidateKey)) {
                session.removeAttribute("lastCandidateSelection");
                session.removeAttribute("lastCandidateSelectionKey");
                persistedCandidateSelection = null;
            }
            originstopid = candResult.originStopId;
            deststopid   = candResult.destStopId;
        } catch (Exception e) {
            out.println("<pre>候補選択エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
            HtmlLayout.renderFoot(out);
            return;
        }

        // 候補選択フォームを結果表示と一緒に表示したい場合は、前回の候補一覧を再描画
        if (persistedCandidateSelection != null && currentCandidateKey.equals(persistedCandidateKey)  && persistedCandidateSelection.status == CandidateSelectionResult.Status.NEED_CHOICE) {
            persistedCandidateSelection.originStopId = originstopid;
            persistedCandidateSelection.destStopId = deststopid;
            RouteSearchView.renderCandidateSelectionForm(out, request, rr, persistedCandidateSelection);
        }

        // -------- 経路探索 --------
        try {
            RouteResult routeResult = executeSearch(originstopid, deststopid, dayType, baseTime);
            
            // 検索結果をセッションに保存
            session.setAttribute("lastOriginStopName", routeResult.lastOriginStopName);
            session.setAttribute("lastOriginStopType", routeResult.lastOriginStopType);
            session.setAttribute("lastDestStopName",   routeResult.lastDestStopName);
            session.setAttribute("lastDestStopType",   routeResult.lastDestStopType);

            session.setAttribute("lastSearchQuery", request.getQueryString());
            session.setAttribute("lastDisplayedResults", routeResult.displayedResults);
            
            // 結果を描画
            RouteSearchView.renderSearchResults(out, request, routeResult);
            
        } catch (Exception e) {
            out.println("<pre>検索エラー: " + esc(String.valueOf(e)) + "</pre>");
            e.printStackTrace();
            HtmlLayout.renderFoot(out);
            return;
        }

        // ==========================================================================

        // -------- foot --------
        HtmlLayout.renderFoot(out);
    }

        // ==========================================================================

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        // POSTで受け取ったパラメータをGETクエリに詰め直してリダイレクト
        String ctx = request.getContextPath();

        String qs =
            "originStop="    + java.net.URLEncoder.encode(nvl(request.getParameter("originStop")),   "UTF-8") +
            "&destStop="     + java.net.URLEncoder.encode(nvl(request.getParameter("destStop")),     "UTF-8") +
            "&dayType="      + java.net.URLEncoder.encode(nvl(request.getParameter("dayType")),      "UTF-8") +
            "&timeType="     + java.net.URLEncoder.encode(nvl(request.getParameter("timeType")),     "UTF-8") +
            "&time="         + java.net.URLEncoder.encode(nvl(request.getParameter("time")),         "UTF-8") +
            "&originStopId=" + java.net.URLEncoder.encode(nvl(request.getParameter("originStopId")), "UTF-8") +
            "&destStopId="   + java.net.URLEncoder.encode(nvl(request.getParameter("destStopId")),   "UTF-8");

        response.sendRedirect(ctx + "/routesearch?" + qs);
    }

    // --------------- メインメソッド系 ---------------

    // 乗換リクエスト解析メソッド
    private RouteRequest parseRequest(HttpServletRequest request) {

        RouteRequest rr = new RouteRequest();

        rr.originStop   = nvl(request.getParameter("originStop"));
        rr.destStop     = nvl(request.getParameter("destStop"));
        rr.timeType     = nvl(request.getParameter("timeType"));
        rr.time         = nvl(request.getParameter("time"));
        rr.dayType          = nvl(request.getParameter("dayType"));
        
        String originStopIdStr = nvl(request.getParameter("originStopId"));
        String destStopIdStr   = nvl(request.getParameter("destStopId"));

        // デフォルト値設定
        if (rr.timeType == null || rr.timeType.isEmpty()) rr.timeType = "now";
        if (rr.dayType == null || rr.dayType.isEmpty()) rr.dayType = "weekday";

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
        if (rr.originStop == null || rr.originStop.isEmpty() || rr.destStop == null || rr.destStop.isEmpty()) {
            rr.errorMessage = "出発地 / 目的地を入力してください";
            return rr;
        }
        if ("spec".equals(rr.timeType) && (rr.time == null || rr.time.isEmpty())) {
            rr.errorMessage = "指定時刻を入力してください";
            return rr;
        }

        // baseTimeに統一
        if ("spec".equals(rr.timeType) && rr.time != null && !rr.time.isEmpty()) {
            rr.baseTime = rr.time;
        } else {
            rr.baseTime = now();
        }
        return rr;
    }

    // 候補選択処理メソッド
    private CandidateSelectionResult selectCandidates(String originStop, String destStop,
            Integer originStopId, Integer destStopId) throws Exception {

        CandidateSelectionResult r = new CandidateSelectionResult();
        r.originStopId = originStopId;
        r.destStopId   = destStopId;

        try (Connection conn = DatabaseConfig.getConnection()) {

            if (r.originStopId == null) r.originCandidates = StopQueries.findByName(conn, originStop, 10);
            if (r.destStopId   == null) r.destCandidates   = StopQueries.findByName(conn, destStop, 10);

            // 0件（見つからない）
            if ((r.originStopId == null && r.originCandidates.isEmpty()) ||
                (r.destStopId   == null && r.destCandidates.isEmpty())) {
                r.status = CandidateSelectionResult.Status.NOT_FOUND;
                r.message = "出発地 / 目的地が見つかりませんでした";
                return r;
            }

            // 1件なら自動確定
            if (r.originStopId == null && r.originCandidates.size() == 1) r.originStopId = r.originCandidates.get(0).id;
            if (r.destStopId   == null && r.destCandidates.size()   == 1) r.destStopId   = r.destCandidates.get(0).id;

            // まだ未確定なら選択が必要
            if (r.originStopId == null || r.destStopId == null) {
                r.status = CandidateSelectionResult.Status.NEED_CHOICE;

                if (r.originStopId != null) r.fixedOriginStop = StopQueries.getStopById(conn, r.originStopId);
                if (r.destStopId   != null) r.fixedDestStop   = StopQueries.getStopById(conn, r.destStopId);

                return r;
            }

            r.status = CandidateSelectionResult.Status.OK;
            return r;
        }
    }

    // メイン検索処理メソッド
    private RouteResult executeSearch(Integer originStopId, Integer destStopId, String dayType, String baseTime) throws Exception {
        RouteResult result = new RouteResult();
        
        Connection conn      = null;
        PreparedStatement ps = null;
        ResultSet rs         = null;
        
        try {
            conn = DatabaseConfig.getConnection();
            
            // 出発地/目的地 を確定 -> その検索に入る
            result.originStop = StopQueries.getStopById(conn, originStopId);
            result.destStop   = StopQueries.getStopById(conn, destStopId);

            // セッションに最後に使った停留所名を保存
            result.lastOriginStopName = result.originStop != null ? result.originStop.name : "";
            result.lastOriginStopType = result.originStop != null ? result.originStop.type : "";
            result.lastDestStopName   = result.destStop   != null ? result.destStop.name   : "";
            result.lastDestStopType   = result.destStop   != null ? result.destStop.type   : "";



            // 出発地 / 目的地 の近くの停留所を探索
            result.stopsNearOrigin = StopQueries.getNearByStops(conn, originStopId, FROM_RADIUS_M, NEAR_LIMIT);
            result.stopsNearDest   = StopQueries.getNearByStops(conn, destStopId, TO_RADIUS_M, NEAR_LIMIT);

            // 出発地 / 目的地 の近くのポートを探索
            result.portsNearOrigin = PortQueries.getNearByPorts(conn, result.originStop.lat, result.originStop.lon, FROM_RADIUS_M, PORT_LIMIT, true, false);
            result.portsNearDest   = PortQueries.getNearByPorts(conn, result.destStop.lat, result.destStop.lon, TO_RADIUS_M, PORT_LIMIT, false, true);

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
            List<BikeDirectPlan> bikeDirectCandidates = new ArrayList<>();

            // 出発地近くの乗車ポートの候補に対して
            for (NearByPorts fromPort : result.portsNearOrigin) {

                int walkToStartPortMin      = walkingMinutes(fromPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                int walkToStartPortDistance = distanceMeters(result.originStop.lat, result.originStop.lon, fromPort.lat, fromPort.lon);
                WalkPath walkToStartPort    = new WalkPath(result.originStop.name, fromPort.portName, walkToStartPortDistance, walkToStartPortMin);
                String bikeStartTime        = addMinutes(baseTime, walkToStartPortMin + BIKE_UNLOCK_MIN);

                // 目的地近くの降車ポートの候補に対して
                for (NearByPorts toPort : result.portsNearDest) {
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
            if(bikeDirectCandidates.size() > BIKE_DIRECT_LIMIT) bikeDirectCandidates = bikeDirectCandidates.subList(0, BIKE_DIRECT_LIMIT);
            for (BikeDirectPlan bp : bikeDirectCandidates) result.results.add(new ResultItem(0, bp.endTime, bp.totalMin, "",bp));



            // ---- part 3 (公共交通 直通) ----
            java.util.Map<Integer, TransitDirectPlan> bestDirectPlanByTripId = new java.util.HashMap<>();

            // 出発地近くの停留所候補に対して
            for (NearByStops boardStop : result.stopsNearOrigin) {

                int walkToBoardStopMin        = walkingMinutes(boardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop      = new WalkPath(result.originStop.name, boardStop.name, boardStop.distance, walkToBoardStopMin);

                // 目的地近くの停留所候補に対して
                for (NearByStops alightStop : result.stopsNearDest) {

                    List<TransitPath> directPathCandidates = TransitQueries.searchDirectTransit(conn, boardStop.stopId, alightStop.stopId, arrivalTimeToBoardStop, dayType, 1);
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

                List<Stop> firstAlightStopCandidates = StopQueries.listTransferCandidates(conn, firstBoardStop.stopId, arrivalTimeTo1BoardStop, dayType, MID_LIMIT);

                // 乗換降車停留所候補に対して
                for (Stop firstAlightStop : firstAlightStopCandidates) {

                    List<TransitPath> leg1Candidates = TransitQueries.searchDirectTransit(conn, firstBoardStop.stopId, firstAlightStop.id, arrivalTimeTo1BoardStop, dayType, 1);
                    if (leg1Candidates.isEmpty()) continue;
                    TransitPath leg1 = leg1Candidates.get(0);

                    String originDepartTime = addMinutes(leg1.depTime, -walkTo1BoardStopMin);

                    List<NearByStops> stopsNearFirstAlight = StopQueries.getNearByStops(conn, firstAlightStop.id, TRANSFER_RADIUS_M, NEAR_LIMIT);

                    // 乗換乗車停留所候補に対して
                    for (NearByStops secondBoardStop : stopsNearFirstAlight) {

                        int walkTransferMin                 = walkingMinutes(secondBoardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                        String arrivalTimeToSecondBoardStop = addMinutes(leg1.arrTime, walkTransferMin);
                        if (diffMinutes(leg1.arrTime, arrivalTimeToSecondBoardStop) < TRANSFER_MIN) continue;
                        WalkPath walkTransfer               = new WalkPath(firstAlightStop.name, secondBoardStop.name, secondBoardStop.distance, walkTransferMin);

                        // 目的地近くの停留所候補に対して
                        for (NearByStops secondAlightStop : result.stopsNearDest) {

                            List<TransitPath> leg2Candidates = TransitQueries.searchDirectTransit(conn, secondBoardStop.stopId, secondAlightStop.stopId, arrivalTimeToSecondBoardStop, dayType, 1);
                            if (leg2Candidates.isEmpty()) continue;
                            TransitPath leg2 = leg2Candidates.get(0);

                            int walkToDestMin      = walkingMinutes(secondAlightStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                            String destArrivalTime = addMinutes(leg2.arrTime, walkToDestMin);
                            WalkPath walkToDest    = new WalkPath(secondAlightStop.name, result.destStop.name, secondAlightStop.distance, walkToDestMin);
                            int totalMin           = diffMinutes(originDepartTime, destArrivalTime);

                            String key = "TT:" + leg1.tripId + "|" + leg2.tripId + "|" + firstAlightStop.id;

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
            java.util.Map<Integer, java.util.List<NearByPorts>> toPortsByOp = groupPortsByOperator(result.portsNearDest);
            final int TRANSFER_BIKE_LIMIT = RESULT_LIMIT * 10;

            // 出発地近くの停留所候補に対して
            for (NearByStops boardStop : result.stopsNearOrigin) {

                int walkToBoardStopMin        = walkingMinutes(boardStop.distance, METER_CORRECTION, METER_PER_MINUTE);
                String arrivalTimeToBoardStop = addMinutes(baseTime, walkToBoardStopMin);
                WalkPath walkToBoardStop      = new WalkPath(result.originStop.name, boardStop.name, boardStop.distance, walkToBoardStopMin);

                List<Stop> firstAlightStopCandidates = StopQueries.listTransferCandidates(conn, boardStop.stopId, arrivalTimeToBoardStop, dayType, MID_LIMIT);

                // 乗換降車停留所候補に対して
                for (Stop firstAlightStop : firstAlightStopCandidates) {

                    List<TransitPath> leg1Candidates = TransitQueries.searchDirectTransit(conn, boardStop.stopId, firstAlightStop.id, arrivalTimeToBoardStop, dayType, 1);
                    if (leg1Candidates.isEmpty()) continue;
                    TransitPath leg1 = leg1Candidates.get(0);

                    String originDepartTime = addMinutes(leg1.depTime, -walkToBoardStopMin);

                    List<NearByPorts> startPortCandidates = PortQueries.getNearByPorts(conn, firstAlightStop.lat, firstAlightStop.lon, BIKE_PORT_RADIUS_M, PORT_LIMIT, true, false);

                    // 乗車ポート候補に対して
                    for (NearByPorts startPort : startPortCandidates) {

                        java.util.List<NearByPorts> returnPortCandidates = toPortsByOp.get(startPort.operatorId);
                        if (returnPortCandidates == null) continue;

                        int walkTransferDistance = distanceMeters(firstAlightStop.lat, firstAlightStop.lon, startPort.lat, startPort.lon);
                        int walkTransferMin      = walkingMinutes(walkTransferDistance, METER_CORRECTION, METER_PER_MINUTE);
                        WalkPath walkTransfer    = new WalkPath(firstAlightStop.name, startPort.portName, walkTransferDistance, walkTransferMin);
                        String bikeStartTime     = addMinutes(leg1.arrTime, TRANSFER_MIN + walkTransferMin + BIKE_UNLOCK_MIN);

                        // 降車ポート候補に対して
                        for (NearByPorts returnPort : returnPortCandidates) {

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
            List<NearByPorts> usePortsCandidates;
            {
                List<Stop> boardStopCandidates = StopQueries.findNearbyStops(conn, result.originStop.lat, result.originStop.lon, BIKE_MAX_RIDE_M);
                List<NearByStops> nearDestTop = result.stopsNearDest.subList(0, Math.min(8, result.stopsNearDest.size()));
                List<Stop> goodBoards = new ArrayList<>();
                for (Stop boardStop : boardStopCandidates) {
                    boolean ok = false;
                    for (NearByStops nsto : nearDestTop) {
                        if (!TransitQueries.searchDirectTransit(conn, boardStop.id, nsto.stopId, baseTime, dayType, 1).isEmpty()) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) goodBoards.add(boardStop);
                }
                usePortsCandidates = collectNearbyPortsFromStops(conn, goodBoards, BIKE_PORT_RADIUS_M, PORT_LIMIT, false, true);
                if (usePortsCandidates.isEmpty()) {
                    usePortsCandidates = PortQueries.getNearByPorts(conn, result.originStop.lat, result.originStop.lon, BIKE_MAX_RIDE_M, 30, false, true);
                }
            }

            final int BIKE_TRANSIT_LIMIT = RESULT_LIMIT * 10;
            List<NearByStops> destStopsForBT = result.stopsNearDest.subList(0, Math.min(25, result.stopsNearDest.size()));

            // 出発地近くのポート候補に対して
            for (NearByPorts startPort : result.portsNearOrigin) {

                if (bestBikeTransitPlanByBTKey.size() >= BIKE_TRANSIT_LIMIT) break;

                int walkToStartPortMin   = walkingMinutes(startPort.distance, METER_CORRECTION, METER_PER_MINUTE);
                WalkPath walkToStartPort = new WalkPath(result.originStop.name, startPort.portName, startPort.distance, walkToStartPortMin);
                String bikeStart         = addMinutes(baseTime, walkToStartPortMin + BIKE_UNLOCK_MIN);

                // 目的地近くのポート候補に対して
                for (NearByPorts returnPort : usePortsCandidates) {

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
                            List<TransitPath> leg2Candidates = TransitQueries.searchDirectTransit(conn, boardstop.id, alightStop.stopId, transitDepartTime, dayType, 1);
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

    // 詳細ページ構築メソッド
    private RouteDetail buildDetailModel(HttpServletRequest req) {
        RouteDetail m = new RouteDetail();

        HttpSession session = req.getSession(false);
        if (session == null) {
            m.error = "セッション切れ";
            m.backUrl = req.getContextPath() + "/routesearch";
            return m;
        }

        Object displayedObj = session.getAttribute("lastDisplayedResults");
        List<ResultItem> displayed;
        if (displayedObj instanceof List<?>) {
            displayed = new ArrayList<>();
            for (Object o : (List<?>) displayedObj) {
                if (o instanceof ResultItem) {
                    displayed.add((ResultItem) o);
                }
            }
        } else {
            displayed = null;
        }
        if (displayed == null) {
            m.error = "検索結果がありません";
            m.backUrl = req.getContextPath() + "/routesearch";
            return m;
        }

        int rid;
        try {
            rid = Integer.parseInt(req.getParameter("rid"));
        } catch (Exception e) {
            m.error = "ridが不正";
            m.backUrl = req.getContextPath() + "/routesearch";
            return m;
        }

        if (rid < 0 || rid >= displayed.size()) {
            m.error = "不正なrid";
            m.backUrl = req.getContextPath() + "/routesearch";
            return m;
        }

        ResultItem item = displayed.get(rid);
        m.item = item;
        m.totalMinutes = item.totalMinutes;
        m.arrivalHHMM = (item.end != null) ? hhmm(item.end.toString()) : "";

        // 戻るURL（条件保持）
        String q = (String) session.getAttribute("lastSearchQuery");
        m.backUrl = req.getContextPath() + "/routesearch" + (q != null ? ("?" + q) : "");

        // payload由来の出発/目的（Viewから移動）
        String payloadOrigin = "";
        String payloadDest = "";
        Object p = item.payload;

        if (p instanceof WalkDirectPlan) {
            WalkDirectPlan wp = (WalkDirectPlan) p;
            payloadOrigin = wp.fromName; payloadDest = wp.toName;
        } else if (p instanceof TransitDirectPlan) {
            TransitDirectPlan dp = (TransitDirectPlan) p;
            payloadOrigin = dp.walk0.fromName != null ? dp.walk0.fromName : dp.leg.fromStopName;
            payloadDest   = dp.walk2.toName   != null ? dp.walk2.toName   : dp.leg.toStopName;
        } else if (p instanceof TransferPath) {
            TransferPath tp = (TransferPath) p;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.leg1.fromStopName;
            payloadDest   = tp.walk2.toName   != null ? tp.walk2.toName   : tp.leg2.toStopName;
        } else if (p instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) p;
            payloadOrigin = bp.walk0.fromName != null ? bp.walk0.fromName : bp.bike.fromPortName;
            payloadDest   = bp.walk2.toName   != null ? bp.walk2.toName   : bp.bike.toPortName;
        } else if (p instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) p;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.leg1.fromStopName;
            payloadDest   = tp.walk2.toName   != null ? tp.walk2.toName   : tp.bike.toPortName;
        } else if (p instanceof TransferBikeTransit) {
            TransferBikeTransit tp = (TransferBikeTransit) p;
            payloadOrigin = tp.walk0.fromName != null ? tp.walk0.fromName : tp.bike.fromPortName;
            payloadDest   = tp.walk2.toName   != null ? tp.walk2.toName   : tp.leg2.toStopName;
        }

        m.originName = preferNonEmpty(toStr(session.getAttribute("lastOriginStopName")), payloadOrigin);
        m.originType = toStr(session.getAttribute("lastOriginStopType"));
        m.destName   = preferNonEmpty(toStr(session.getAttribute("lastDestStopName")), payloadDest);
        m.destType   = toStr(session.getAttribute("lastDestStopType"));

        // 自転車予約用情報（Viewから移動）
        RouteDetail.ReservationInfo ri = extractReservationInfo(item);
        m.reservation = ri;

        return m;
    }

    // 自転車予約情報抽出メソッド
    private RouteDetail.ReservationInfo extractReservationInfo(ResultItem item) {
        Object p = item.payload;

        boolean hasBike =
            (p instanceof BikeDirectPlan) ||
            (p instanceof TransferTransitBike) ||
            (p instanceof TransferBikeTransit);

        if (!hasBike) return null;

        RouteDetail.ReservationInfo ri = new RouteDetail.ReservationInfo();

        if (p instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) p;
            ri.operatorId = bp.bike.operatorId;
            ri.operatorName = bp.bike.operatorName;
            ri.operatorContact = bp.bike.operatorContact;
            ri.startPortId = bp.bike.fromPortId;
            ri.endPortId = bp.bike.toPortId;
        } else if (p instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) p;
            ri.operatorId = tp.bike.operatorId;
            ri.operatorName = tp.bike.operatorName;
            ri.operatorContact = tp.bike.operatorContact;
            ri.startPortId = tp.bike.fromPortId;
            ri.endPortId = tp.bike.toPortId;
        } else {
            TransferBikeTransit tp = (TransferBikeTransit) p;
            ri.operatorId = tp.bike.operatorId;
            ri.operatorName = tp.bike.operatorName;
            ri.operatorContact = tp.bike.operatorContact;
            ri.startPortId = tp.bike.fromPortId;
            ri.endPortId = tp.bike.toPortId;
        }

        return ri;
    }
    
}
