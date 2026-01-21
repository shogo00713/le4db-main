package view;

import java.io.PrintWriter;
import javax.servlet.http.HttpServletRequest;

import model.BikeDirectPlan;
import model.ResultItem;
import model.RouteResult;
import model.TransferBikeTransit;
import model.TransferPath;
import model.TransferTransitBike;
import model.TransitDirectPlan;
import model.WalkDirectPlan;

import static util.HtmlUtils.esc;
import static util.HtmlUtils.option;
import static util.HtmlUtils.safeColor;
import static util.RouteConstants.RESULT_LIMIT;
import static util.RouteSearchUtils.isZeroWalk;
import static util.TimeUtils.hhmm;

public class RouteSearchView {

    // ヘッダー
    public static void renderHeader(PrintWriter out, HttpServletRequest request, String title, String bodyClass) {
        String ctx = request.getContextPath();

        out.println("<div class=\"header\">");

        // タイトル
        out.println("<div>");
        out.println("<h2 class=\"title\">マルチモーダル路線検索</h2>");
        out.println("<p class=\"subtitle\">公共交通 + シェアサイクル の複合型乗換検索</p>");
        out.println("</div>");

        // 管理者ボタン
        out.println("<a class=\"adminbtn\" href=\"" + ctx + "/portadmin/\">シェアサイクル管理者画面</a>");

        out.println("</div>");
    }

    // 検索フォーム
    public static void renderSearchForm(PrintWriter out, 
            HttpServletRequest request,
            String originstop, String deststop,
            String day, String timemode, String timevalue) {
        
        String ctx = request.getContextPath();

        out.println("<div class=\"card\">");
        out.println("<form class=\"form\" action=\"" + ctx + "/routesearch\" method=\"GET\">");

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

        out.println("</div>"); // form card
        out.println("</div>"); // card
    }

    // 検索結果
    public static void renderSearchResults(PrintWriter out, HttpServletRequest request, RouteResult routeResult) {
        out.println("<div class=\"card\">");
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

        out.println("</table></div></div><br/>");
    }


// -------- 便利メソッド --------

    // --- 経路表示（HTMLタグ）用ヘルパ ---
    public static String tagArrow() {
        return "<span class=\"arrow-mini\">→</span>";
    }
    public static String tagWalk(String minutes) {
        return "<span class=\"tag walk\">徒歩 " + esc(minutes) + "</span>";
    }
    public static String tagTransfer() {
        return "<span class=\"tag transfer\">乗換</span>";
    }
    public static String tagBike(String rideMinutes) {
        // operatorName を出したいならここで表示
        return "<span class=\"tag bike\">シェアサイクル " + esc(rideMinutes) + "</span>";
    }
    public static String tagLine(String routeName, String routeColor) {
        String c = safeColor(routeColor);
        return "<span class=\"tag line\" style=\"--line:" + c + "\">" + esc(routeName) + "</span>";
    }

    // --- 各プランの「経路」セル(HTML)生成 ---
    public static String pathHtml(WalkDirectPlan wp) {
        return "<div class=\"path\">" + tagWalk(wp.totalMin + "分") + "</div>";
    }
    public static String pathHtml(TransitDirectPlan dp) {
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
    public static String pathHtml(TransferPath tp) {
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
    public static String pathHtml(BikeDirectPlan bp) {
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"path\">");
        if (!isZeroWalk(bp.walk0)) h.append(tagWalk(bp.walk0.min + "分")).append(tagArrow());
        h.append(tagBike(bp.bike.rideMinutes + "分"));
        if (!isZeroWalk(bp.walk2)) h.append(tagArrow()).append(tagWalk(bp.walk2.min + "分"));
        h.append("</div>");
        return h.toString();
    }
    public static String pathHtml(TransferTransitBike tp) {
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
    public static String pathHtml(TransferBikeTransit tp) {
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

    // 徒歩のみ の結果を表示
    public static void printWalkDirectRow(PrintWriter out, WalkDirectPlan wp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(wp) + "</td>");
        out.println("<td>" + esc(hhmm(wp.startTime)) + " → " + esc(hhmm(wp.endTime)) + "</td>");
        out.println("<td>" + wp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }
    // 自転車のみ の結果を表示
    public static void printBikeDirectRow(PrintWriter out, BikeDirectPlan bp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(bp) + "</td>");
        out.println("<td>" + esc(hhmm(bp.startTime)) + " → " + esc(hhmm(bp.endTime)) + "</td>");
        out.println("<td>" + bp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }
    // 直通 の結果を表示
    public static void printDirectRow(PrintWriter out, TransitDirectPlan dp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(dp) + "</td>");
        out.println("<td>" + esc(hhmm(dp.startTime)) + " → " + esc(hhmm(dp.endTime)) + "</td>");
        out.println("<td>" + dp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }
    // 乗換あり の結果表示
    public static void printTransferRow(PrintWriter out, TransferPath tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }
    // 公共交通 -> 自転車 の結果を表示
    public static void printTransitBikeRow(PrintWriter out, TransferTransitBike tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");
    }
    // 自転車 -> 公共交通 の結果を表示
    public static void printBikeTransitRow(PrintWriter out, TransferBikeTransit tp, String detailUrl) {
        out.println("<tr>");
        out.println("<td>" + pathHtml(tp) + "</td>");
        out.println("<td>" + esc(hhmm(tp.startTime)) + " → " + esc(hhmm(tp.endTime)) + "</td>");
        out.println("<td>" + tp.totalMin + "分</td>");
        out.println("<td><a class=\"detailbtn\" href=\"" + esc(detailUrl) + "\">詳細</a></td>");
        out.println("</tr>");

    }
}
