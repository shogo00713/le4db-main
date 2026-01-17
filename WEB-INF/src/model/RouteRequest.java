package model;

/**
 * 経路検索リクエストの情報
 * @oriqnStop 出発地停留所名
 * @destStop  目的地停留所名
 * @timeMode "now" or "spec"
 * @timeValue "HH:mm" 形式の時刻文字列
 * @day "平日" or "休日"
 * @originStopId 出発地停留所ID (見つかった場合)
 * @destStopId   目的地停留所ID (見つかった場合)
 * @baseTime 検索基準時刻 "HH:mm"
 * @errorMessage エラーメッセージ (エラーがない場合は null)
 */

public class RouteRequest {
    public String originStop;
    public String destStop;
    public String timeMode;
    public String timeValue;
    public String day;
    public Integer originStopId;
    public Integer destStopId;
    public String baseTime;
    public String errorMessage;

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
}