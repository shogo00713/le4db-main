package view;

import static util.HtmlUtils.esc;
import static util.HtmlUtils.safeColor;
import static util.RouteSearchUtils.isZeroWalk;
import static util.TimeUtils.diffMinutes;
import static util.TimeUtils.hhmm;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import model.BikeDirectPlan;
import model.ResultItem;
import model.RouteDetail;
import model.TransferBikeTransit;
import model.TransferPath;
import model.TransferTransitBike;
import model.TransitDirectPlan;
import model.WalkDirectPlan;
import model.WalkPath;

public class RouteDetailView {

 public static void renderDetailPage(HttpServletRequest req, HttpServletResponse resp, RouteDetail m) throws IOException {
    resp.setContentType("text/html; charset=UTF-8");
    PrintWriter out = resp.getWriter();

    // エラー表示（Viewは表示だけ）
    if (m == null || m.error != null) {
        out.println("<!DOCTYPE html><html lang='ja'><head>");
        out.println("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>");
        out.println("<title>Route Detail</title>");
        out.println("<link rel=\"stylesheet\" href=\"" + req.getContextPath() + "/static/app.css\"/>");
        out.println("</head><body class='page-route-detail'>");
        out.println("<div class='detail-card'>");
        out.println("<a href='" + esc(m != null ? m.backUrl : (req.getContextPath()+"/routesearch")) + "' class='back-btn'>← 戻る</a>");
        out.println("<div class='detail-header'>エラー</div>");
        out.println("<p>" + esc(m != null ? m.error : "不明なエラー") + "</p>");
        out.println("</div></body></html>");
        return;
    }

    // --- HTML ---
    out.println("<!DOCTYPE html><html lang='ja'><head>");
    out.println("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>");
    out.println("<title>Route Detail</title>");
    out.println("<link rel=\"stylesheet\" href=\"" + req.getContextPath() + "/static/app.css\"/>");
    out.println("</head><body class='page-route-detail'>");

    out.println("<div class='detail-card'>");
    out.println("<a href='" + esc(m.backUrl) + "' class='back-btn'>← 戻る</a>");
    out.println("<div class='detail-header'>ルート詳細</div>");

    out.println("<div class='detail-summary'>");
    out.println("<div class='summary-grid'>");
    out.println(summaryItem("出発地", m.originName, (m.originType != null && !m.originType.isEmpty()) ? m.originType : ""));
    out.println(summaryItem("目的地", m.destName, (m.destType != null && !m.destType.isEmpty()) ? m.destType : ""));
    out.println(summaryItem("所要時間", m.totalMinutes + "分", (m.arrivalHHMM != null && !m.arrivalHHMM.isEmpty()) ? ("到着 " + m.arrivalHHMM) : ""));
    out.println("</div>");
    out.println("</div>");

    ResultItem item = m.item;
    if (item == null) {
        out.println("<p class='alert'>詳細データが見つかりませんでした</p>");
    } else {
        String arrow = " <span class='arrow-mini'>→</span> ";
        out.println("<div class='steps'>");

        if (item.payload instanceof WalkDirectPlan) {
            WalkDirectPlan wp = (WalkDirectPlan) item.payload;
            String main = esc(wp.fromName) + arrow + esc(wp.toName) + " (" + hhmm(wp.startTime) + "→" + hhmm(wp.endTime) + ")";
            String meta = chipInfo("距離 約" + wp.distanceM + "m") + chipInfo("時間 " + wp.totalMin + "分");
            printStep(out, "徒歩", main, meta);
        }
        else if (item.payload instanceof TransitDirectPlan) {
            TransitDirectPlan dp = (TransitDirectPlan) item.payload;
            printWalk(dp.walk0, out, arrow);
            String mainRide = esc(dp.leg.fromStopName) + " " + hhmm(dp.leg.depTime) + arrow + esc(dp.leg.toStopName) + " " + hhmm(dp.leg.arrTime);
            String metaRide = chipLine(dp.leg.routeName, dp.leg.routeColor) + chipTrip(dp.leg.tripName) + chipInfo("方向 " + dp.leg.destinationDirection) + chipInfo("時間 " + diffMinutes(dp.leg.depTime, dp.leg.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);
            printWalk(dp.walk2, out, arrow);
        }
        else if (item.payload instanceof TransferPath) {
            TransferPath tp = (TransferPath) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow + esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor) + chipTrip(tp.leg1.tripName) + chipInfo("方向 " + tp.leg1.destinationDirection) + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);
            if (!isZeroWalk(tp.walk1)) {
                String mainWalk1 = esc(tp.walk1.fromName) + arrow + esc(tp.walk1.toName);
                String metaWalk1 = chipInfo("距離 約" + tp.walk1.dist + "m") + chipInfo("時間 " + tp.walk1.min + "分");
                printStep(out, "徒歩", mainWalk1, metaWalk1);
            } else {
                printStep(out, "乗換", "同一地点で乗換", "");
            }
            String mainRide2 = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide2 = chipLine(tp.leg2.routeName, tp.leg2.routeColor) + chipTrip(tp.leg2.tripName) + chipInfo("方向 " + tp.leg2.destinationDirection) + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide2, metaRide2);
            printWalk(tp.walk2, out, arrow);
        }
        else if (item.payload instanceof BikeDirectPlan) {
            BikeDirectPlan bp = (BikeDirectPlan) item.payload;
            printWalk(bp.walk0, out, arrow);
            String mainBike = esc(bp.bike.fromPortName) + " " + hhmm(bp.bike.startTime) + arrow + esc(bp.bike.toPortName) + " " + hhmm(bp.bike.endTime);
            String metaBike = chipInfo("距離 約" + bp.bike.distanceM + "m") + chipInfo("時間 " + bp.bike.rideMinutes + "分") + chipInfo("事業者 " + bp.bike.operatorName) + chipContact(bp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(bp.walk2, out, arrow);
        }
        else if (item.payload instanceof TransferTransitBike) {
            TransferTransitBike tp = (TransferTransitBike) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainRide1 = esc(tp.leg1.fromStopName) + " " + hhmm(tp.leg1.depTime) + arrow+ esc(tp.leg1.toStopName) + " " + hhmm(tp.leg1.arrTime);
            String metaRide1 = chipLine(tp.leg1.routeName, tp.leg1.routeColor) + chipTrip(tp.leg1.tripName) + chipInfo("方向 " + tp.leg1.destinationDirection) + chipInfo("時間 " + diffMinutes(tp.leg1.depTime, tp.leg1.arrTime) + "分");
            printStep(out, "乗車", mainRide1, metaRide1);
            printWalk(tp.walk1, out, arrow);
            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m") + chipInfo("時間 " + tp.bike.rideMinutes + "分") + chipInfo("事業者 " + tp.bike.operatorName) + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(tp.walk2, out, arrow);
        }
        else if (item.payload instanceof TransferBikeTransit) {
            TransferBikeTransit tp = (TransferBikeTransit) item.payload;
            printWalk(tp.walk0, out, arrow);
            String mainBike = esc(tp.bike.fromPortName) + " " + hhmm(tp.bike.startTime) + arrow + esc(tp.bike.toPortName) + " " + hhmm(tp.bike.endTime);
            String metaBike = chipInfo("距離 約" + tp.bike.distanceM + "m") + chipInfo("時間 " + tp.bike.rideMinutes + "分") + chipInfo("事業者 " + tp.bike.operatorName) + chipContact(tp.bike.operatorContact);
            printStep(out, "自転車", mainBike, metaBike);
            printWalk(tp.walk1, out, arrow);
            String mainRide = esc(tp.leg2.fromStopName) + " " + hhmm(tp.leg2.depTime) + arrow + esc(tp.leg2.toStopName) + " " + hhmm(tp.leg2.arrTime);
            String metaRide = chipLine(tp.leg2.routeName, tp.leg2.routeColor) + chipTrip(tp.leg2.tripName) + chipInfo("方向 " + tp.leg2.destinationDirection) + chipInfo("時間 " + diffMinutes(tp.leg2.depTime, tp.leg2.arrTime) + "分");
            printStep(out, "乗車", mainRide, metaRide);
            printWalk(tp.walk2, out, arrow);
        }

        out.println("</div>");
    }


    // 予約セクション：m.reservation を使う（View側で抽出しない）
    if (m.reservation != null) {
        out.println("<div class='reservation-section'>");
        out.println("<div class='reservation-title'>🚲 シェアサイクルを予約</div>");
        out.println("<div class='contact-card'>");
        out.println("<p class='contact-name'>" + esc(m.reservation.operatorName) + "</p>");
        out.println("<p class='contact-contact'>" + esc(m.reservation.operatorContact) + "</p>");
        out.println("</div>");

        out.println("<div class='reservation-actions'>");
        out.println("<button class='btn-reserve' id='reserveBtn' onclick='reserveBike(" + m.reservation.operatorId + ")'>予約する</button>");
        out.println("<button class='btn-action' id='startBtn' style='display:none;' onclick='startBikeUsage()'>利用開始</button>");
        out.println("<button class='btn-action' id='returnBtn' style='display:none;' onclick='returnBikeUsage()'>返却</button>");
        out.println("<div class='cancel-area'>");
        out.println("<button class='btn-cancel' id='cancelBtn' style='display:none;' onclick='cancelBikeReservation()'>キャンセル</button>");
        out.println("<span id='timerPill' class='timer-pill' style='display:none;'></span>");
        out.println("</div>");
        out.println("<div id=\"statusMsg\" class=\"reservation-status is-success\" style=\"display:none;\">");
        out.println("<span class=\"status-icon\">✓</span>");
        out.println("<span class=\"status-text\"></span>");
        out.println("</div>");
        out.println("</div>");

        out.println("<script src='" + req.getContextPath() + "/static/bike-reservation.js'></script>");
        out.println("<script>");
        out.println("initBikeReservation({");
        out.println("  startPortId: " + m.reservation.startPortId + ",");
        out.println("  endPortId: " + m.reservation.endPortId + ",");
        out.println("  apiEndpoint: '" + req.getContextPath() + "/bikereservation'");
        out.println("});");
        out.println("</script>");
    }

    out.println("</div>");
    out.println("</body></html>");
}


// -------- 便利メソッド --------

    public static String chipLine(String routeName, String routeColor) {
        if (routeName == null || routeName.trim().isEmpty())
            return "";
        String c = safeColor(routeColor);
        return "<span class=\"chip line\" style=\"--line:" + c + "\"><span class=\"dot\"></span>"
                + esc(routeName) + "</span>";
    }
    public static String chipTrip(String tripName) {
        if (tripName == null || tripName.trim().isEmpty())
            return "";
        return "<span class=\"chip trip\">便名 " + esc(tripName) + "</span>";
    }
    public static String chipInfo(String text) {
        if (text == null || text.trim().isEmpty())
            return "";
        return "<span class=\"chip info\">" + esc(text) + "</span>";
    }
    public static String chipContact(String contact) {
        if (contact == null || contact.trim().isEmpty())
            return "";
        return "<span class=\"chip contact\">連絡先 " + esc(contact) + "</span>";
    }
    public static void printStep(PrintWriter out, String kind, String mainHtml, String metaHtml) {
        out.println("<div class=\"step\">"
                + "<span class=\"kind\">" + esc(kind) + "</span>"
                + "<span class=\"main\">" + mainHtml + "</span>"
                + "<span class=\"meta\">" + metaHtml + "</span>"
                + "</div>");
    }
    public static String summaryItem(String label, String value, String sub) {
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
    public static void printWalk (WalkPath w, PrintWriter out, String arrow) {
        if(!isZeroWalk(w)) {
            String main = esc(w.fromName) + arrow + esc(w.toName);
            String meta = chipInfo("距離 約" + w.dist + "m") + chipInfo("時間 " + w.min + "分");
            printStep(out, "徒歩", main, meta);
        }
    }

}
    