package model;

/**
 * 経路検索リクエストの情報
 * @originStop 出発地停留所名
 * @destStop  目的地停留所名
 * @timeType "now" or "spec"
 * @time "HH:mm" 形式の時刻文字列
 * @dayType "weekday" or "holiday"
 * @originStopId 出発地停留所ID (見つかった場合)
 * @destStopId   目的地停留所ID (見つかった場合)
 * @baseTime 検索基準時刻 "HH:mm"
 * @errorMessage エラーメッセージ (エラーがない場合は null)
 */

public class RouteRequest {
    public String originStop;
    public String destStop;
    public String timeType;
    public String time;
    public String dayType;
    public Integer originStopId;
    public Integer destStopId;
    public String baseTime;
    public String errorMessage;

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
}