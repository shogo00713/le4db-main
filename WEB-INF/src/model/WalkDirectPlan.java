package model;

/**
 * 徒歩直行プラン情報
 * @fromName 出発地名
 * @toName 目的地名
 * @distanceM 距離(m)
 * @totalMin 所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class WalkDirectPlan {
    public final String fromName;
    public final String toName;
    public final int distanceM;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public WalkDirectPlan(String fromName, String toName, int distanceM, int totalMin, String startTime, String endTime) {
        this.fromName = fromName;
        this.toName = toName;
        this.distanceM = distanceM;
        this.totalMin = totalMin;
        this.startTime = startTime;
            this.endTime = endTime;
        }
    }